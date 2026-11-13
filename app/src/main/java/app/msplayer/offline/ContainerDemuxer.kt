package app.msplayer.offline

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.zip.Inflater

/**
 * High-performance buffered container demuxer for Matroska (MKV / WebM) and MP4/MOV
 * embedded subtitles & technical stream metadata. Supports zlib/header-stripped Matroska
 * ContentEncodings, SeekHead out-of-order lookup, MP4 tx3g/sbtl text tracks,
 * persistent cache, and cooperative cancellation.
 */
object ContainerDemuxer {

    data class EmbeddedTrack(
        val trackNumber: Int,
        val type: Int, // 1 = Video, 2 = Audio, 17 = Subtitle
        val codecId: String,
        val name: String,
        val language: String,
        val compressionAlgo: Int = -1, // -1 none, 0 zlib, 3 header strip
        val compressionSettings: ByteArray? = null,
    )

    data class TrackDetail(
        val trackNumber: Int,
        val width: Int = 0,
        val height: Int = 0,
        val fps: Double = 0.0,
        val sampleRateHz: Int = 0,
        val channels: Int = 0,
        val bitDepth: Int = 0,
    )

    data class ContainerInfo(
        val format: String = "",
        val encodingSoftware: String = "",
        val durationMs: Long = 0L,
        val trackDetails: Map<Int, TrackDetail> = emptyMap(),
    )

    data class ChapterMarker(
        val startMs: Long,
        val title: String,
    )

    data class DemuxResult(
        val tracks: Map<Int, EmbeddedTrack>,
        val cuesByTrack: Map<Int, List<PlaybackRules.SubtitleCue>>,
        val chapters: List<ChapterMarker> = emptyList(),
        val containerInfo: ContainerInfo = ContainerInfo(),
    )

    private val memoryCache = LruCache<String, DemuxResult>(12)

    private class BufferedChannelReader(private val channel: FileChannel) {
        val fileLength: Long = runCatching { channel.size() }.getOrDefault(0L)
        private val buf = ByteArray(1048576) // 1 MB read-ahead minimizes FUSE/ScopedStorage syscalls
        private var bufStart = 0L
        private var bufLen = 0
        private var bufPos = 0

        fun position(): Long = bufStart + bufPos

        fun seek(target: Long) {
            val clamped = target.coerceIn(0L, fileLength)
            if (clamped >= bufStart && clamped <= bufStart + bufLen) {
                bufPos = (clamped - bufStart).toInt()
            } else {
                bufStart = clamped
                bufPos = 0
                bufLen = 0
            }
        }

        private fun refill(): Boolean {
            val pos = position()
            if (pos >= fileLength) return false
            channel.position(pos)
            val bb = ByteBuffer.wrap(buf)
            val n = channel.read(bb)
            if (n <= 0) return false
            bufStart = pos
            bufLen = n
            bufPos = 0
            return true
        }

        fun readByte(): Int {
            if (bufPos >= bufLen && !refill()) return -1
            return buf[bufPos++].toInt() and 0xFF
        }

        fun readShortSigned(): Int {
            val hi = readByte()
            val lo = readByte()
            if (hi < 0 || lo < 0) return 0
            return ((hi shl 8) or lo).toShort().toInt()
        }

        fun readUInt(byteCount: Int): Long {
            var v = 0L
            for (i in 0 until byteCount) {
                val b = readByte()
                if (b < 0) return v
                v = (v shl 8) or b.toLong()
            }
            return v
        }

        fun readFloat(byteCount: Int): Double {
            if (byteCount != 4 && byteCount != 8) {
                if (byteCount > 0) seek(position() + byteCount)
                return 0.0
            }
            val raw = readUInt(byteCount)
            return if (byteCount == 4) {
                java.lang.Float.intBitsToFloat(raw.toInt()).toDouble()
            } else {
                java.lang.Double.longBitsToDouble(raw)
            }
        }

        fun readFully(dst: ByteArray): Boolean {
            var out = 0
            while (out < dst.size) {
                if (bufPos >= bufLen && !refill()) return false
                val avail = minOf(bufLen - bufPos, dst.size - out)
                System.arraycopy(buf, bufPos, dst, out, avail)
                bufPos += avail
                out += avail
            }
            return true
        }

        fun readVintSize(): Long? {
            if (bufPos < bufLen) {
                val b0 = buf[bufPos].toInt() and 0xFF
                if ((b0 and 0x80) != 0) {
                    bufPos++
                    val v = (b0 and 0x7F).toLong()
                    return if (v == 0x7FL) -1L else v
                }
            }
            val b = readByte()
            if (b == -1) return null
            var mask = 0x80
            var length = 1
            while (mask > 0 && (b and mask) == 0) {
                mask = mask ushr 1
                length++
            }
            if (length > 8) return null
            var value = (b and (mask - 1)).toLong()
            for (i in 0 until length - 1) {
                val nextByte = readByte()
                if (nextByte == -1) return null
                value = (value shl 8) or (nextByte.toLong() and 0xFFL)
            }
            val unknownMarker = if (length == 8) 0x00FFFFFFFFFFFFFFL else (1L shl (7 * length)) - 1L
            return if (value == unknownMarker) -1L else value
        }

        fun readVintValue(): Long? {
            if (bufPos < bufLen) {
                val b0 = buf[bufPos].toInt() and 0xFF
                if ((b0 and 0x80) != 0) {
                    bufPos++
                    return (b0 and 0x7F).toLong()
                }
            }
            val b = readByte()
            if (b == -1) return null
            var mask = 0x80
            var length = 1
            while (mask > 0 && (b and mask) == 0) {
                mask = mask ushr 1
                length++
            }
            if (length > 8) return null
            var value = (b and (mask - 1)).toLong()
            for (i in 0 until length - 1) {
                val nextByte = readByte()
                if (nextByte == -1) return null
                value = (value shl 8) or (nextByte.toLong() and 0xFFL)
            }
            return value
        }

        fun readId(): Long? {
            if (bufPos < bufLen) {
                val b0 = buf[bufPos].toInt() and 0xFF
                if ((b0 and 0x80) != 0) {
                    bufPos++
                    return b0.toLong()
                }
            }
            val b = readByte()
            if (b == -1) return null
            var mask = 0x80
            var length = 1
            while (mask > 0 && (b and mask) == 0) {
                mask = mask ushr 1
                length++
            }
            if (length > 4) return null
            var eid = b.toLong() and 0xFFL
            for (i in 0 until length - 1) {
                val nextByte = readByte()
                if (nextByte == -1) return null
                eid = (eid shl 8) or (nextByte.toLong() and 0xFFL)
            }
            return eid
        }

        fun elementEnd(startPos: Long, size: Long): Long =
            if (size < 0L) fileLength else (startPos + size).coerceIn(startPos, fileLength)
    }

    private fun decompressPayload(payload: ByteArray, track: EmbeddedTrack?): ByteArray {
        val algo = track?.compressionAlgo ?: -1
        if (algo == 0) {
            return runCatching {
                val inflater = Inflater()
                try {
                    inflater.setInput(payload)
                    val out = ByteArrayOutputStream(maxOf(64, payload.size * 2))
                    val buf = ByteArray(4096)
                    var safety = 0
                    while (!inflater.finished() && safety++ < 256) {
                        val n = inflater.inflate(buf)
                        if (n > 0) out.write(buf, 0, n)
                        else if (inflater.needsInput() || inflater.needsDictionary()) break
                        else break
                    }
                    out.toByteArray()
                } finally {
                    inflater.end()
                }
            }.getOrDefault(payload)
        }
        if (algo == 3) {
            val prefix = track?.compressionSettings
            if (prefix != null && prefix.isNotEmpty()) {
                return ByteArray(prefix.size + payload.size).apply {
                    System.arraycopy(prefix, 0, this, 0, prefix.size)
                    System.arraycopy(payload, 0, this, prefix.size, payload.size)
                }
            }
        }
        return payload
    }

    private fun isTextSubtitleCodec(codecId: String): Boolean {
        val c = codecId.trim()
        if (c.isEmpty()) return true
        return c.startsWith("S_TEXT", ignoreCase = true) ||
            c.contains("WEBVTT", ignoreCase = true) ||
            c.equals("S_KATE", ignoreCase = true) ||
            c.equals("TEXT", ignoreCase = true)
    }

    private fun cacheKeyFor(context: Context, uri: Uri, localFile: File?): String? {
        if (localFile != null) {
            val len = localFile.length()
            val mod = localFile.lastModified()
            if (len <= 0L || PlaybackRules.temporaryFile(localFile.name)) return null
            return "v2|file|${localFile.absolutePath}|$len|$mod"
        }
        val nameHint = uri.lastPathSegment ?: ""
        if (PlaybackRules.temporaryFile(nameHint)) return null
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE, "date_modified"), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val sz = if (c.isNull(0)) 0L else c.getLong(0)
                    val mod = if (c.isNull(1)) 0L else c.getLong(1)
                    if (sz > 0L) "v2|uri|$uri|$sz|$mod" else null
                } else null
            }
        }.getOrNull()
    }

    private fun cacheFile(context: Context, key: String): File {
        val dir = File(context.cacheDir, "demux-v2").apply { mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$digest.bin")
    }

    private fun readDiskCache(context: Context, key: String): DemuxResult? = runCatching {
        val f = cacheFile(context, key)
        if (!f.isFile || f.length() < 12) return@runCatching null
        DataInputStream(BufferedInputStream(FileInputStream(f), 32768)).use { dis ->
            if (dis.readInt() != 0x4D534432) return@runCatching null // "MSD2"
            val fmt = dis.readUTF()
            val sw = dis.readUTF()
            val durMs = dis.readLong()
            val detailCount = dis.readInt()
            val details = linkedMapOf<Int, TrackDetail>()
            repeat(detailCount) {
                val tn = dis.readInt()
                details[tn] = TrackDetail(
                    trackNumber = tn,
                    width = dis.readInt(),
                    height = dis.readInt(),
                    fps = dis.readDouble(),
                    sampleRateHz = dis.readInt(),
                    channels = dis.readInt(),
                    bitDepth = dis.readInt(),
                )
            }
            val trackCount = dis.readInt()
            val tracks = linkedMapOf<Int, EmbeddedTrack>()
            repeat(trackCount) {
                val tn = dis.readInt()
                val type = dis.readInt()
                val codecId = dis.readUTF()
                val name = dis.readUTF()
                val lang = dis.readUTF()
                val algo = dis.readInt()
                val settingsLen = dis.readInt()
                val settings = if (settingsLen > 0) ByteArray(settingsLen).also { dis.readFully(it) } else null
                tracks[tn] = EmbeddedTrack(tn, type, codecId, name, lang, algo, settings)
            }
            val cueTrackCount = dis.readInt()
            val cues = linkedMapOf<Int, List<PlaybackRules.SubtitleCue>>()
            repeat(cueTrackCount) {
                val tn = dis.readInt()
                val count = dis.readInt()
                val list = ArrayList<PlaybackRules.SubtitleCue>(count)
                repeat(count) {
                    val s = dis.readLong()
                    val e = dis.readLong()
                    val bytesLen = dis.readInt()
                    val txt = if (bytesLen > 0) {
                        val b = ByteArray(bytesLen)
                        dis.readFully(b)
                        String(b, Charsets.UTF_8)
                    } else ""
                    list.add(PlaybackRules.SubtitleCue(s, e, txt))
                }
                cues[tn] = list
            }
            val chCount = dis.readInt()
            val chapters = ArrayList<ChapterMarker>(chCount)
            repeat(chCount) {
                chapters.add(ChapterMarker(dis.readLong(), dis.readUTF()))
            }
            DemuxResult(tracks, cues, chapters, ContainerInfo(fmt, sw, durMs, details))
        }
    }.getOrNull()

    private fun writeDiskCache(context: Context, key: String, result: DemuxResult) {
        runCatching {
            val target = cacheFile(context, key)
            val tmp = File(target.parentFile, "${target.name}.tmp")
            DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 32768)).use { dos ->
                dos.writeInt(0x4D534432)
                dos.writeUTF(result.containerInfo.format)
                dos.writeUTF(result.containerInfo.encodingSoftware)
                dos.writeLong(result.containerInfo.durationMs)
                dos.writeInt(result.containerInfo.trackDetails.size)
                result.containerInfo.trackDetails.values.forEach { d ->
                    dos.writeInt(d.trackNumber)
                    dos.writeInt(d.width)
                    dos.writeInt(d.height)
                    dos.writeDouble(d.fps)
                    dos.writeInt(d.sampleRateHz)
                    dos.writeInt(d.channels)
                    dos.writeInt(d.bitDepth)
                }
                dos.writeInt(result.tracks.size)
                result.tracks.values.forEach { t ->
                    dos.writeInt(t.trackNumber)
                    dos.writeInt(t.type)
                    dos.writeUTF(t.codecId)
                    dos.writeUTF(t.name)
                    dos.writeUTF(t.language)
                    dos.writeInt(t.compressionAlgo)
                    val cs = t.compressionSettings
                    if (cs == null || cs.isEmpty()) {
                        dos.writeInt(0)
                    } else {
                        dos.writeInt(cs.size)
                        dos.write(cs)
                    }
                }
                dos.writeInt(result.cuesByTrack.size)
                result.cuesByTrack.forEach { (tn, list) ->
                    dos.writeInt(tn)
                    dos.writeInt(list.size)
                    list.forEach { cue ->
                        dos.writeLong(cue.startMs)
                        dos.writeLong(cue.endMs)
                        val bytes = cue.text.toByteArray(Charsets.UTF_8)
                        dos.writeInt(bytes.size)
                        dos.write(bytes)
                    }
                }
                dos.writeInt(result.chapters.size)
                result.chapters.forEach { ch ->
                    dos.writeLong(ch.startMs)
                    dos.writeUTF(ch.title)
                }
            }
            if (!tmp.renameTo(target)) tmp.delete()
            val dir = target.parentFile ?: return@runCatching
            val files = dir.listFiles()?.filter { it.extension == "bin" }?.sortedByDescending { it.lastModified() }.orEmpty()
            var total = 0L
            files.forEach { f ->
                total += f.length()
                if (total > 24L * 1024L * 1024L) f.delete()
            }
        }
    }

    /** Fast header-only probe (<5 ms) for Format / Encoding SW / FPS / Audio sample rate. */
    fun probeContainerInfo(
        context: Context,
        uri: Uri,
        localFile: File?,
        extHint: String,
    ): ContainerInfo {
        val key = cacheKeyFor(context, uri, localFile)
        if (key != null) {
            memoryCache.get(key)?.let { return it.containerInfo }
            readDiskCache(context, key)?.let {
                memoryCache.put(key, it)
                return it.containerInfo
            }
        }
        val ext = extHint.lowercase()
        if (ext == "mp4" || ext == "m4v" || ext == "mov") {
            val mp4 = if (localFile != null) parseMp4(localFile) else parseMp4(context, uri)
            if (mp4.containerInfo.format.isNotBlank() || mp4.tracks.isNotEmpty()) return mp4.containerInfo
        }
        return if (localFile != null && localFile.exists() && localFile.canRead() && localFile.length() >= 12) {
            runCatching { FileInputStream(localFile).use { parseChannel(it.channel, null, { false }, headersOnly = true).containerInfo } }.getOrDefault(ContainerInfo())
        } else {
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val ch = FileInputStream(pfd.fileDescriptor).channel
                    ch.position(0)
                    parseChannel(ch, null, { false }, headersOnly = true).containerInfo
                }
            }.getOrNull() ?: ContainerInfo()
        }
    }

    /** Unified dispatcher for MKV/WebM and MP4/M4V/MOV embedded soft subtitles. */
    fun parseContainer(
        context: Context,
        uri: Uri,
        localFile: File?,
        extHint: String,
        onProgressBatch: ((Map<Int, EmbeddedTrack>, Map<Int, List<PlaybackRules.SubtitleCue>>) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
    ): DemuxResult {
        val cacheKey = cacheKeyFor(context, uri, localFile)
        if (cacheKey != null) {
            memoryCache.get(cacheKey)?.let { cached ->
                if (cached.tracks.isNotEmpty() || cached.cuesByTrack.isNotEmpty() || cached.containerInfo.format.isNotBlank()) {
                    return cached
                }
            }
            readDiskCache(context, cacheKey)?.let { cached ->
                memoryCache.put(cacheKey, cached)
                return cached
            }
        }
        if (isCancelled()) return DemuxResult(emptyMap(), emptyMap())

        val ext = extHint.lowercase()
        val result = if (ext == "mp4" || ext == "m4v" || ext == "mov") {
            val mp4 = if (localFile != null) parseMp4(localFile) else parseMp4(context, uri)
            if (mp4.tracks.isNotEmpty() || mp4.cuesByTrack.isNotEmpty() || mp4.containerInfo.format.isNotBlank()) mp4
            else if (localFile != null) parseMkv(localFile, onProgressBatch, isCancelled)
            else parseMkv(context, uri, onProgressBatch, isCancelled)
        } else {
            if (localFile != null) parseMkv(localFile, onProgressBatch, isCancelled)
            else parseMkv(context, uri, onProgressBatch, isCancelled)
        }

        if (!isCancelled() && cacheKey != null && (result.tracks.isNotEmpty() || result.cuesByTrack.isNotEmpty())) {
            memoryCache.put(cacheKey, result)
            writeDiskCache(context, cacheKey, result)
        }
        return result
    }

    /** Extracts embedded text subtitle tracks/cues from an MKV/WebM file URI or FD. */
    fun parseMkv(
        context: Context,
        uri: Uri,
        onProgressBatch: ((Map<Int, EmbeddedTrack>, Map<Int, List<PlaybackRules.SubtitleCue>>) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
    ): DemuxResult {
        if (uri.scheme == "file") {
            val f = uri.path?.let(::File)
            if (f != null && f.exists()) return parseMkv(f, onProgressBatch, isCancelled)
        }
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                parseMkv(pfd.fileDescriptor, onProgressBatch, isCancelled)
            } ?: DemuxResult(emptyMap(), emptyMap())
        }.getOrDefault(DemuxResult(emptyMap(), emptyMap()))
    }

    fun parseMkv(
        file: File,
        onProgressBatch: ((Map<Int, EmbeddedTrack>, Map<Int, List<PlaybackRules.SubtitleCue>>) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
    ): DemuxResult {
        if (!file.exists() || !file.canRead() || file.length() < 12) {
            return DemuxResult(emptyMap(), emptyMap())
        }
        return runCatching {
            FileInputStream(file).use { fis -> parseChannel(fis.channel, onProgressBatch, isCancelled) }
        }.getOrDefault(DemuxResult(emptyMap(), emptyMap()))
    }

    fun parseMkv(
        fd: FileDescriptor,
        onProgressBatch: ((Map<Int, EmbeddedTrack>, Map<Int, List<PlaybackRules.SubtitleCue>>) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
    ): DemuxResult {
        if (!fd.valid()) return DemuxResult(emptyMap(), emptyMap())
        return runCatching {
            val channel = FileInputStream(fd).channel
            channel.position(0)
            parseChannel(channel, onProgressBatch, isCancelled)
        }.getOrDefault(DemuxResult(emptyMap(), emptyMap()))
    }

    private fun isTopSegmentId(id: Long): Boolean = when (id) {
        0x114D9B74L, // SeekHead
        0x1549A966L, // Info
        0x1654AE6BL, // Tracks
        0x1F43B675L, // Cluster
        0x1C53BB6BL, // Cues
        0x1941A469L, // Attachments
        0x1043A770L, // Chapters
        0x1254C367L, // Tags
        0x18538067L, // Segment
        0x1A45DFA3L -> true // EBML Header
        else -> false
    }

    private fun parseChannel(
        channel: FileChannel,
        onProgressBatch: ((Map<Int, EmbeddedTrack>, Map<Int, List<PlaybackRules.SubtitleCue>>) -> Unit)? = null,
        isCancelled: () -> Boolean = { false },
        headersOnly: Boolean = false,
    ): DemuxResult {
        val reader = BufferedChannelReader(channel)
        val fileLength = reader.fileLength
        if (fileLength < 12L) return DemuxResult(emptyMap(), emptyMap())

        val tracks = linkedMapOf<Int, EmbeddedTrack>()
        val trackDetails = linkedMapOf<Int, TrackDetail>()
        val cues = linkedMapOf<Int, MutableList<PlaybackRules.SubtitleCue>>()
        val chapters = mutableListOf<ChapterMarker>()

        var timecodeScale = 1_000_000L // 1 ms default
        var rawDurationTicks = 0.0
        var muxingApp = ""
        var writingApp = ""
        var docType = "matroska"
        var clusterTimecode = 0L
        var lastEmittedCueTotal = 0
        var lastEmitAtMs = 0L
        var segmentDataStart = -1L
        var seekTracksPos: Long? = null
        var seekChaptersPos: Long? = null
        var jumpedTracksDone = false

        fun parseTracksElement(tracksEnd: Long) {
            while (reader.position() < tracksEnd) {
                if (isCancelled()) return
                val teId = reader.readId() ?: break
                val teSize = reader.readVintSize() ?: break
                val tePos = reader.position()
                val teEnd = reader.elementEnd(tePos, teSize)
                if (teId == 0xAEL) { // TrackEntry
                    var tNum: Int? = null
                    var tType: Int? = null
                    var tCodec = ""
                    var tName = ""
                    var tLang = "und"
                    var compAlgo = -1
                    var compSettings: ByteArray? = null
                    var defaultDurationNs = 0L
                    var vWidth = 0
                    var vHeight = 0
                    var aSampleRate = 0
                    var aChannels = 0
                    var aBitDepth = 0

                    while (reader.position() < teEnd) {
                        val fid = reader.readId() ?: break
                        val fsize = reader.readVintSize() ?: break
                        val fpos = reader.position()
                        val fEnd = reader.elementEnd(fpos, fsize)
                        when (fid) {
                            0xD7L -> if (fsize in 1..4) tNum = reader.readUInt(fsize.toInt()).toInt()
                            0x83L -> if (fsize in 1..4) tType = reader.readUInt(fsize.toInt()).toInt()
                            0x23E383L -> if (fsize in 1..8) defaultDurationNs = reader.readUInt(fsize.toInt())
                            0x86L -> if (fsize in 1..256) {
                                val bytes = ByteArray(fsize.toInt())
                                if (reader.readFully(bytes)) tCodec = String(bytes, Charsets.US_ASCII).trim('\u0000')
                            }
                            0x536EL -> if (fsize in 1..1024) {
                                val bytes = ByteArray(fsize.toInt())
                                if (reader.readFully(bytes)) tName = String(bytes, Charsets.UTF_8).trim('\u0000')
                            }
                            0x22B59CL -> if (fsize in 1..64) {
                                val bytes = ByteArray(fsize.toInt())
                                if (reader.readFully(bytes)) tLang = String(bytes, Charsets.US_ASCII).trim('\u0000')
                            }
                            0x22B59DL -> if (fsize in 1..64 && tLang == "und") { // LanguageBCP47
                                val bytes = ByteArray(fsize.toInt())
                                if (reader.readFully(bytes)) tLang = String(bytes, Charsets.US_ASCII).trim('\u0000')
                            }
                            0xE0L -> { // Video settings
                                while (reader.position() < fEnd) {
                                    val vid = reader.readId() ?: break
                                    val vsize = reader.readVintSize() ?: break
                                    val vpos = reader.position()
                                    val vEnd = reader.elementEnd(vpos, vsize)
                                    when (vid) {
                                        0xB0L -> if (vsize in 1..4) vWidth = reader.readUInt(vsize.toInt()).toInt()
                                        0xBAL -> if (vsize in 1..4) vHeight = reader.readUInt(vsize.toInt()).toInt()
                                    }
                                    reader.seek(vEnd)
                                }
                            }
                            0xE1L -> { // Audio settings
                                while (reader.position() < fEnd) {
                                    val aid = reader.readId() ?: break
                                    val asize = reader.readVintSize() ?: break
                                    val apos = reader.position()
                                    val aEnd = reader.elementEnd(apos, asize)
                                    when (aid) {
                                        0xB5L -> if (asize == 4L || asize == 8L) aSampleRate = reader.readFloat(asize.toInt()).toInt()
                                        0x9FL -> if (asize in 1..4) aChannels = reader.readUInt(asize.toInt()).toInt()
                                        0x6264L -> if (asize in 1..4) aBitDepth = reader.readUInt(asize.toInt()).toInt()
                                    }
                                    reader.seek(aEnd)
                                }
                            }
                            0x6D80L -> { // ContentEncodings
                                while (reader.position() < fEnd) {
                                    val ceId = reader.readId() ?: break
                                    val ceSize = reader.readVintSize() ?: break
                                    val cePos = reader.position()
                                    val ceEnd = reader.elementEnd(cePos, ceSize)
                                    if (ceId == 0x6240L) { // ContentEncoding
                                        while (reader.position() < ceEnd) {
                                            val subId = reader.readId() ?: break
                                            val subSize = reader.readVintSize() ?: break
                                            val subPos = reader.position()
                                            val subEnd = reader.elementEnd(subPos, subSize)
                                            if (subId == 0x5034L) { // ContentCompression
                                                compAlgo = 0 // EBML default for ContentCompAlgo is 0 (zlib)
                                                while (reader.position() < subEnd) {
                                                    val cId = reader.readId() ?: break
                                                    val cSize = reader.readVintSize() ?: break
                                                    val cPos = reader.position()
                                                    val cEnd = reader.elementEnd(cPos, cSize)
                                                    when (cId) {
                                                        0x4254L -> if (cSize in 1..4) compAlgo = reader.readUInt(cSize.toInt()).toInt()
                                                        0x4255L -> if (cSize in 1..256) {
                                                            val bytes = ByteArray(cSize.toInt())
                                                            if (reader.readFully(bytes)) compSettings = bytes
                                                        }
                                                    }
                                                    reader.seek(cEnd)
                                                }
                                            }
                                            reader.seek(subEnd)
                                        }
                                    }
                                    reader.seek(ceEnd)
                                }
                            }
                        }
                        reader.seek(fEnd)
                    }
                    if (tNum != null && tType != null) {
                        tracks[tNum] = EmbeddedTrack(tNum, tType, tCodec, tName, tLang, compAlgo, compSettings)
                        val fps = if (defaultDurationNs > 0L) 1_000_000_000.0 / defaultDurationNs.toDouble() else 0.0
                        trackDetails[tNum] = TrackDetail(
                            trackNumber = tNum,
                            width = vWidth,
                            height = vHeight,
                            fps = fps,
                            sampleRateHz = aSampleRate,
                            channels = aChannels,
                            bitDepth = aBitDepth,
                        )
                        if (tType == 17 && isTextSubtitleCodec(tCodec) && tNum !in cues) {
                            cues[tNum] = mutableListOf()
                        }
                    }
                }
                reader.seek(teEnd)
            }
        }

        fun resolveChaptersFromSeekHead() {
            if (chapters.isEmpty() && seekChaptersPos != null) {
                val cPos = seekChaptersPos!!
                if (cPos in 0 until fileLength) {
                    reader.seek(cPos)
                    if (reader.readId() == 0x1043A770L) {
                        val cSize = reader.readVintSize()
                        if (cSize != null) parseChapters(reader, reader.elementEnd(reader.position(), cSize), chapters)
                    }
                }
            }
        }

        while (reader.position() < fileLength) {
            if (isCancelled()) break
            val eid = reader.readId() ?: break
            val size = reader.readVintSize() ?: break
            val pos = reader.position()

            when (eid) {
                0x1A45DFA3L -> { // EBML Header
                    val ebmlEnd = reader.elementEnd(pos, size)
                    while (reader.position() < ebmlEnd) {
                        val bid = reader.readId() ?: break
                        val bsize = reader.readVintSize() ?: break
                        val bpos = reader.position()
                        val bend = reader.elementEnd(bpos, bsize)
                        if (bid == 0x4282L && bsize in 1..64) { // DocType
                            val bytes = ByteArray(bsize.toInt())
                            if (reader.readFully(bytes)) docType = String(bytes, Charsets.US_ASCII).trim('\u0000')
                        }
                        reader.seek(bend)
                    }
                    reader.seek(ebmlEnd)
                }
                0x18538067L -> {
                    // Segment container: remember data base for SeekHead relative offsets
                    if (segmentDataStart < 0L) segmentDataStart = pos
                    continue
                }
                0x114D9B74L -> {
                    // SeekHead: record Tracks / Chapters offsets in case Tracks appear after Clusters
                    val shEnd = reader.elementEnd(pos, size)
                    if (segmentDataStart >= 0L) {
                        while (reader.position() < shEnd) {
                            val sId = reader.readId() ?: break
                            val sSize = reader.readVintSize() ?: break
                            val sPos = reader.position()
                            val sEnd = reader.elementEnd(sPos, sSize)
                            if (sId == 0x4DBBL) { // Seek
                                var targetId: Long? = null
                                var targetRel: Long? = null
                                while (reader.position() < sEnd) {
                                    val fId = reader.readId() ?: break
                                    val fSize = reader.readVintSize() ?: break
                                    val fPos = reader.position()
                                    val fEnd = reader.elementEnd(fPos, fSize)
                                    when (fId) {
                                        0x53ABL -> if (fSize in 1..4) targetId = reader.readUInt(fSize.toInt())
                                        0x53ACL -> if (fSize in 1..8) targetRel = reader.readUInt(fSize.toInt())
                                    }
                                    reader.seek(fEnd)
                                }
                                if (targetRel != null && targetId != null) {
                                    val absPos = segmentDataStart + targetRel
                                    if (targetId == 0x1654AE6BL && seekTracksPos == null) seekTracksPos = absPos
                                    if (targetId == 0x1043A770L && seekChaptersPos == null) seekChaptersPos = absPos
                                }
                            }
                            reader.seek(sEnd)
                        }
                    }
                    reader.seek(shEnd)
                }
                0x1549A966L -> {
                    // Info
                    val infoEnd = reader.elementEnd(pos, size)
                    while (reader.position() < infoEnd) {
                        val iid = reader.readId() ?: break
                        val isize = reader.readVintSize() ?: break
                        val ipos = reader.position()
                        val iEnd = reader.elementEnd(ipos, isize)
                        when (iid) {
                            0x2AD7B1L -> if (isize in 1..8) {
                                val ts = reader.readUInt(isize.toInt())
                                if (ts > 0) timecodeScale = ts
                            }
                            0x4489L -> if (isize == 4L || isize == 8L) {
                                rawDurationTicks = reader.readFloat(isize.toInt())
                            }
                            0x4D80L -> if (isize in 1..512) {
                                val bytes = ByteArray(isize.toInt())
                                if (reader.readFully(bytes)) muxingApp = String(bytes, Charsets.UTF_8).trim('\u0000', ' ')
                            }
                            0x5741L -> if (isize in 1..512) {
                                val bytes = ByteArray(isize.toInt())
                                if (reader.readFully(bytes)) writingApp = String(bytes, Charsets.UTF_8).trim('\u0000', ' ')
                            }
                        }
                        reader.seek(iEnd)
                    }
                    reader.seek(infoEnd)
                }
                0x1654AE6BL -> {
                    // Tracks
                    val tracksEnd = reader.elementEnd(pos, size)
                    parseTracksElement(tracksEnd)
                    reader.seek(tracksEnd)
                }
                0x1F43B675L -> {
                    // If Tracks weren't seen before Cluster but SeekHead pointed to them, parse Tracks first
                    if (tracks.isEmpty() && !jumpedTracksDone && seekTracksPos != null) {
                        jumpedTracksDone = true
                        val resumeClusterData = pos
                        val tPos = seekTracksPos!!
                        if (tPos in 0 until fileLength) {
                            reader.seek(tPos)
                            if (reader.readId() == 0x1654AE6BL) {
                                val tSize = reader.readVintSize()
                                if (tSize != null) parseTracksElement(reader.elementEnd(reader.position(), tSize))
                            }
                        }
                        resolveChaptersFromSeekHead()
                        reader.seek(resumeClusterData)
                    } else if (chapters.isEmpty() && seekChaptersPos != null && !headersOnly) {
                        val resumeClusterData = pos
                        resolveChaptersFromSeekHead()
                        reader.seek(resumeClusterData)
                    }
                    if (headersOnly) break
                    // Early stop if there are NO text subtitle tracks to parse!
                    if (cues.isEmpty()) break

                    val clusterEnd = reader.elementEnd(pos, size)
                    var clusterItems = 0
                    while (reader.position() < clusterEnd) {
                        if ((clusterItems++ and 0xFF) == 0 && isCancelled()) break
                        val elemStart = reader.position()
                        val cid = reader.readId() ?: break
                        if (size < 0L && isTopSegmentId(cid)) {
                            reader.seek(elemStart)
                            break
                        }
                        val csize = reader.readVintSize() ?: break
                        val cpos = reader.position()
                        val cend = reader.elementEnd(cpos, csize)
                        when (cid) {
                            0xE7L -> { // Cluster Timecode
                                if (csize in 1..8) clusterTimecode = reader.readUInt(csize.toInt())
                            }
                            0xA3L -> { // SimpleBlock
                                if (csize > 4) {
                                    val tNum = reader.readVintValue()?.toInt() ?: -1
                                    val trackCues = cues[tNum]
                                    if (trackCues == null) {
                                        reader.seek(cend)
                                        continue
                                    }
                                    val relTime = reader.readShortSigned()
                                    reader.readByte() // flags
                                    val headerLen = (reader.position() - cpos).toInt()
                                    val payloadLen = (csize - headerLen).toInt()
                                    if (payloadLen in 1..262144) {
                                        val payload = ByteArray(payloadLen)
                                        if (reader.readFully(payload)) {
                                            val decoded = decompressPayload(payload, tracks[tNum])
                                            val startMs = ((clusterTimecode + relTime) * (timecodeScale / 1_000_000.0)).toLong()
                                            val rawText = String(decoded, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
                                            val cleanText = sanitizeSubtitle(rawText, tracks[tNum]?.codecId ?: "")
                                            if (cleanText.isNotBlank()) {
                                                trackCues.add(PlaybackRules.SubtitleCue(startMs, startMs + 3000L, cleanText))
                                            }
                                        }
                                    }
                                }
                            }
                            0xA0L -> { // BlockGroup
                                var bgTrack = -1
                                var bgRelTime = 0
                                var bgPayload: ByteArray? = null
                                var bgDur = 3000L
                                while (reader.position() < cend) {
                                    val bgId = reader.readId() ?: break
                                    val bgSize = reader.readVintSize() ?: break
                                    val bgPos = reader.position()
                                    val bgEnd = reader.elementEnd(bgPos, bgSize)
                                    when (bgId) {
                                        0xA1L -> { // Block
                                            if (bgSize > 4) {
                                                val tNum = reader.readVintValue()?.toInt() ?: -1
                                                if (tNum !in cues) {
                                                    reader.seek(bgEnd)
                                                    // Fast-skip rest of BlockGroup for video/audio tracks
                                                    break
                                                }
                                                val relTime = reader.readShortSigned()
                                                reader.readByte() // flags
                                                val hLen = (reader.position() - bgPos).toInt()
                                                val pLen = (bgSize - hLen).toInt()
                                                if (pLen in 1..262144) {
                                                    val payload = ByteArray(pLen)
                                                    if (reader.readFully(payload)) {
                                                        bgTrack = tNum
                                                        bgRelTime = relTime
                                                        bgPayload = payload
                                                    }
                                                }
                                            }
                                        }
                                        0x9BL -> { // BlockDuration
                                            if (bgPayload != null && bgSize in 1..8) bgDur = reader.readUInt(bgSize.toInt())
                                        }
                                    }
                                    reader.seek(bgEnd)
                                }
                                val payload = bgPayload
                                if (payload != null) {
                                    val track = tracks[bgTrack]
                                    val decoded = decompressPayload(payload, track)
                                    val startMs = ((clusterTimecode + bgRelTime) * (timecodeScale / 1_000_000.0)).toLong()
                                    val endMs = startMs + (bgDur * (timecodeScale / 1_000_000.0)).toLong()
                                    val rawText = String(decoded, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
                                    val cleanText = sanitizeSubtitle(rawText, track?.codecId ?: "")
                                    if (cleanText.isNotBlank()) {
                                        cues[bgTrack]?.add(PlaybackRules.SubtitleCue(startMs, endMs.coerceAtLeast(startMs + 400L), cleanText))
                                    }
                                }
                            }
                        }
                        reader.seek(cend)
                    }
                    reader.seek(clusterEnd)
                    if (onProgressBatch != null && !isCancelled()) {
                        val totalNow = cues.values.sumOf { it.size }
                        val nowMs = System.currentTimeMillis()
                        val shouldEmit =
                            (lastEmittedCueTotal == 0 && totalNow > 0) ||
                                (totalNow - lastEmittedCueTotal >= 250 && nowMs - lastEmitAtMs >= 450L)
                        if (shouldEmit) {
                            lastEmittedCueTotal = totalNow
                            lastEmitAtMs = nowMs
                            onProgressBatch(tracks.toMap(), cues.mapValues { (_, list) -> list.sortedBy { it.startMs } })
                        }
                    }
                }
                0x1043A770L -> {
                    // Chapters
                    val chEnd = reader.elementEnd(pos, size)
                    parseChapters(reader, chEnd, chapters)
                    reader.seek(chEnd)
                }
                else -> {
                    if (size < 0L) break
                    reader.seek(reader.elementEnd(pos, size))
                }
            }
        }

        val finalCues = cues.mapValues { (_, list) -> list.sortedBy { it.startMs } }
        val finalChapters = chapters
            .distinctBy { it.startMs }
            .sortedBy { it.startMs }
            .mapIndexed { idx, ch ->
                if (ch.title.isBlank()) ch.copy(title = "Chapter ${idx + 1}") else ch
            }
        val encodingSw = listOf(muxingApp, writingApp)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" + ")
        val formatLabel = if (docType.equals("webm", ignoreCase = true)) "WebM / Matroska" else "Matroska / WebM"
        val durationMs = ((rawDurationTicks * timecodeScale) / 1_000_000.0).toLong().coerceAtLeast(0L)
        return DemuxResult(
            tracks = tracks,
            cuesByTrack = finalCues,
            chapters = finalChapters,
            containerInfo = ContainerInfo(
                format = formatLabel,
                encodingSoftware = encodingSw,
                durationMs = durationMs,
                trackDetails = trackDetails,
            ),
        )
    }

    private fun parseChapters(
        reader: BufferedChannelReader,
        chaptersEnd: Long,
        out: MutableList<ChapterMarker>,
    ) {
        while (reader.position() < chaptersEnd) {
            val id = reader.readId() ?: break
            val size = reader.readVintSize() ?: break
            val pos = reader.position()
            val end = reader.elementEnd(pos, size)
            if (id == 0x45B9L) { // EditionEntry
                while (reader.position() < end) {
                    val aid = reader.readId() ?: break
                    val asize = reader.readVintSize() ?: break
                    val apos = reader.position()
                    val aend = reader.elementEnd(apos, asize)
                    if (aid == 0xB6L) { // ChapterAtom
                        parseChapterAtom(reader, aend, out)
                    }
                    reader.seek(aend)
                }
            } else if (id == 0xB6L) {
                parseChapterAtom(reader, end, out)
            }
            reader.seek(end)
        }
    }

    private fun parseChapterAtom(
        reader: BufferedChannelReader,
        atomEnd: Long,
        out: MutableList<ChapterMarker>,
    ) {
        var startNs: Long? = null
        var title = ""
        while (reader.position() < atomEnd) {
            val id = reader.readId() ?: break
            val size = reader.readVintSize() ?: break
            val pos = reader.position()
            val end = reader.elementEnd(pos, size)
            when (id) {
                0x91L -> if (size in 1..8) startNs = reader.readUInt(size.toInt()) // ChapterTimeStart (ns)
                0x80L -> { // ChapterDisplay
                    while (reader.position() < end) {
                        val did = reader.readId() ?: break
                        val dsize = reader.readVintSize() ?: break
                        val dpos = reader.position()
                        val dend = reader.elementEnd(dpos, dsize)
                        if (did == 0x85L && dsize in 1..1024 && title.isBlank()) { // ChapString
                            val bytes = ByteArray(dsize.toInt())
                            if (reader.readFully(bytes)) {
                                title = String(bytes, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
                            }
                        }
                        reader.seek(dend)
                    }
                }
                0xB6L -> parseChapterAtom(reader, end, out) // Nested ChapterAtom
            }
            reader.seek(end)
        }
        if (startNs != null) {
            out.add(ChapterMarker(startMs = (startNs / 1_000_000L).coerceAtLeast(0L), title = title))
        }
    }

    /**
     * Cleans ASS/SSA formatting overrides and headers while preserving clean multi-line dialogue.
     * Strips vector drawing commands ({\p1}...{\p0} and vector coordinate strings).
     */
    fun sanitizeSubtitle(raw: String, codecId: String): String {
        var text = raw.replace("\r", "").trim { it <= ' ' || it == '\u0000' }
        if (codecId.contains("ASS", ignoreCase = true) || codecId.contains("SSA", ignoreCase = true)) {
            // Matroska ASS blocks: "ReadOrder, Layer, Style, Name, MarginL, MarginR, MarginV, Effect, Text"
            var commaCount = 0
            var textStart = 0
            for (i in text.indices) {
                if (text[i] == ',') {
                    commaCount++
                    if (commaCount == 8) {
                        textStart = i + 1
                        break
                    }
                }
            }
            if (commaCount >= 8 && textStart < text.length) {
                text = text.substring(textStart)
            }
            // Strip ASS drawing vector blocks {\p1}...{\p0} or until end of string
            text = text.replace(Regex("""\{[^}]*\\p[1-9][^}]*\}.*?(\{[^}]*\\p0[^}]*\}|$)""", RegexOption.DOT_MATCHES_ALL), "")
            // Strip remaining ASS tags {...}
            text = text.replace(Regex("""\{[^}]*\}"""), "")
            // Replace \N, \n with newlines and \h with spaces
            text = text.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
        }
        val trimmed = text.trim()
        if (trimmed.matches(Regex("""^(?:[mnlbspc]\s+-?\d+(?:\s+-?\d+)*\s*)+$""", RegexOption.IGNORE_CASE))) {
            return ""
        }
        return trimmed
    }

    /** Extracts MP4/MOV/M4V soft-text subtitle tracks (tx3g / sbtl / text). */
    fun parseMp4(context: Context, uri: Uri): DemuxResult {
        if (uri.scheme == "file") {
            uri.path?.let(::File)?.takeIf { it.exists() }?.let { return parseMp4(it) }
        }
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { fis ->
                    val ch = fis.channel
                    ch.position(0)
                    parseMp4Channel(ch)
                }
            } ?: DemuxResult(emptyMap(), emptyMap())
        }.getOrDefault(DemuxResult(emptyMap(), emptyMap()))
    }

    fun parseMp4(file: File): DemuxResult {
        if (!file.exists() || !file.canRead() || file.length() < 16) return DemuxResult(emptyMap(), emptyMap())
        return runCatching {
            FileInputStream(file).use { fis -> parseMp4Channel(fis.channel) }
        }.getOrDefault(DemuxResult(emptyMap(), emptyMap()))
    }

    private fun parseMp4Channel(channel: FileChannel): DemuxResult {
        val reader = BufferedChannelReader(channel)
        val fileLength = reader.fileLength
        val tracks = linkedMapOf<Int, EmbeddedTrack>()
        val cues = linkedMapOf<Int, List<PlaybackRules.SubtitleCue>>()

        fun readBoxHeader(limit: Long): Triple<String, Long, Long>? {
            val start = reader.position()
            if (start + 8 > limit) return null
            val rawSize = reader.readUInt(4)
            val typeBytes = ByteArray(4)
            if (!reader.readFully(typeBytes)) return null
            val type = String(typeBytes, Charsets.ISO_8859_1)
            val size = when (rawSize) {
                0L -> limit - start
                1L -> {
                    if (start + 16 > limit) return null
                    reader.readUInt(8)
                }
                else -> rawSize
            }
            val end = (start + size).coerceAtMost(limit)
            if (end <= reader.position() && size > 0L) return null
            return Triple(type, reader.position(), end)
        }

        fun decodeMdhdLang(raw: Int): String {
            if (raw <= 0) return "und"
            val c1 = ((raw ushr 10) and 0x1F) + 0x60
            val c2 = ((raw ushr 5) and 0x1F) + 0x60
            val c3 = (raw and 0x1F) + 0x60
            return charArrayOf(c1.toChar(), c2.toChar(), c3.toChar()).concatToString()
        }

        fun scanContainer(limit: Long, action: (String, Long, Long) -> Unit) {
            while (reader.position() + 8 <= limit) {
                val (type, dataStart, boxEnd) = readBoxHeader(limit) ?: break
                action(type, dataStart, boxEnd)
                reader.seek(boxEnd)
            }
        }

        var nextTrackNum = 1
        var mp4EncodingSw = ""
        var mp4DurationMs = 0L
        val mp4Details = linkedMapOf<Int, TrackDetail>()
        scanContainer(fileLength) { topType, _, topEnd ->
            if (topType == "moov") {
                scanContainer(topEnd) { moovChild, mcStart, moovChildEnd ->
                    when (moovChild) {
                        "mvhd" -> {
                            val ver = reader.readByte()
                            reader.readUInt(3)
                            val ts: Long
                            val dur: Long
                            if (ver == 1) {
                                reader.readUInt(16)
                                ts = reader.readUInt(4).coerceAtLeast(1L)
                                dur = reader.readUInt(8)
                            } else {
                                reader.readUInt(8)
                                ts = reader.readUInt(4).coerceAtLeast(1L)
                                dur = reader.readUInt(4)
                            }
                            if (dur > 0L) mp4DurationMs = (dur * 1000L) / ts
                        }
                        "udta" -> {
                            scanContainer(moovChildEnd) { uType, _, uEnd ->
                                if (uType == "meta" && uEnd - reader.position() > 12) {
                                    reader.readUInt(4) // version/flags
                                    scanContainer(uEnd) { mType, _, mEnd ->
                                        if (mType == "ilst") {
                                            scanContainer(mEnd) { itemType, _, itemEnd ->
                                                if (itemType == "\u00A9too") {
                                                    scanContainer(itemEnd) { dType, dStart, dEnd ->
                                                        if (dType == "data" && dEnd - dStart > 8) {
                                                            reader.readUInt(8)
                                                            val len = (dEnd - reader.position()).coerceIn(0L, 256L).toInt()
                                                            if (len > 0) {
                                                                val b = ByteArray(len)
                                                                if (reader.readFully(b)) mp4EncodingSw = String(b, Charsets.UTF_8).trim('\u0000', ' ')
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        "trak" -> {
                            var trackId = nextTrackNum++
                            var handlerType = ""
                            var timescale = 1000L
                            var lang = "und"
                            var format = ""
                            var trackName = ""
                            var vWidth = 0
                            var vHeight = 0
                            var aSampleRate = 0
                            var aChannels = 0
                            val stts = mutableListOf<Pair<Long, Long>>() // count, delta
                            val stsz = mutableListOf<Int>()
                            val chunkOffsets = mutableListOf<Long>()
                            val stsc = mutableListOf<Triple<Int, Int, Int>>() // firstChunk, samplesPerChunk, descIdx

                            fun scanTrakBox(tLimit: Long) {
                                scanContainer(tLimit) { bType, bStart, bEnd ->
                                    when (bType) {
                                        "tkhd" -> {
                                            val ver = reader.readByte()
                                            reader.seek(bStart + if (ver == 1) 20L else 12L)
                                            val id = reader.readUInt(4).toInt()
                                            if (id > 0) trackId = id
                                        }
                                        "mdia", "minf", "stbl", "udta" -> scanTrakBox(bEnd)
                                        "mdhd" -> {
                                            val ver = reader.readByte()
                                            reader.readUInt(3)
                                            if (ver == 1) {
                                                reader.readUInt(16)
                                                timescale = reader.readUInt(4).coerceAtLeast(1L)
                                                reader.readUInt(8)
                                            } else {
                                                reader.readUInt(8)
                                                timescale = reader.readUInt(4).coerceAtLeast(1L)
                                                reader.readUInt(4)
                                            }
                                            lang = decodeMdhdLang(reader.readUInt(2).toInt())
                                        }
                                        "hdlr" -> {
                                            reader.seek(bStart + 8L)
                                            val sub = ByteArray(4)
                                            if (reader.readFully(sub)) handlerType = String(sub, Charsets.ISO_8859_1)
                                            val rem = (bEnd - reader.position()).coerceIn(0L, 256L).toInt()
                                            if (rem > 12) {
                                                reader.seek(reader.position() + 12)
                                                val nameBytes = ByteArray((bEnd - reader.position()).coerceIn(0L, 128L).toInt())
                                                if (reader.readFully(nameBytes)) {
                                                    val s = String(nameBytes, Charsets.UTF_8).trim('\u0000', ' ')
                                                    if (s.isNotBlank() && !s.contains("Handler", ignoreCase = true)) trackName = s
                                                }
                                            }
                                        }
                                        "stsd" -> {
                                            reader.readUInt(4) // ver + flags
                                            val count = reader.readUInt(4)
                                            if (count >= 1 && reader.position() + 8 <= bEnd) {
                                                readBoxHeader(bEnd)?.let { (entryType, entryStart, entryEnd) ->
                                                    format = entryType
                                                    if (handlerType == "vide" && entryEnd - entryStart >= 28) {
                                                        reader.seek(entryStart + 24L)
                                                        vWidth = reader.readUInt(2).toInt()
                                                        vHeight = reader.readUInt(2).toInt()
                                                    } else if (handlerType == "soun" && entryEnd - entryStart >= 28) {
                                                        reader.seek(entryStart + 16L)
                                                        aChannels = reader.readUInt(2).toInt()
                                                        reader.readUInt(2) // sample size
                                                        reader.readUInt(4) // pre-defined + reserved
                                                        aSampleRate = (reader.readUInt(4) ushr 16).toInt()
                                                    }
                                                }
                                            }
                                        }
                                        "stts" -> {
                                            reader.readUInt(4)
                                            val count = reader.readUInt(4).coerceAtMost(50_000L).toInt()
                                            repeat(count) {
                                                stts.add(reader.readUInt(4) to reader.readUInt(4))
                                            }
                                        }
                                        "stsz" -> {
                                            reader.readUInt(4)
                                            val uniform = reader.readUInt(4).toInt()
                                            val count = reader.readUInt(4).coerceAtMost(50_000L).toInt()
                                            if (uniform > 0) {
                                                repeat(count) { stsz.add(uniform) }
                                            } else {
                                                repeat(count) { stsz.add(reader.readUInt(4).toInt()) }
                                            }
                                        }
                                        "stsc" -> {
                                            reader.readUInt(4)
                                            val count = reader.readUInt(4).coerceAtMost(50_000L).toInt()
                                            repeat(count) {
                                                stsc.add(Triple(reader.readUInt(4).toInt(), reader.readUInt(4).toInt(), reader.readUInt(4).toInt()))
                                            }
                                        }
                                        "stco" -> {
                                            reader.readUInt(4)
                                            val count = reader.readUInt(4).coerceAtMost(50_000L).toInt()
                                            repeat(count) { chunkOffsets.add(reader.readUInt(4)) }
                                        }
                                        "co64" -> {
                                            reader.readUInt(4)
                                            val count = reader.readUInt(4).coerceAtMost(50_000L).toInt()
                                            repeat(count) { chunkOffsets.add(reader.readUInt(8)) }
                                        }
                                    }
                                }
                            }

                            scanTrakBox(moovChildEnd)
                            val trackType = when (handlerType) {
                                "vide" -> 1
                                "soun" -> 2
                                "sbtl", "text", "subp" -> 17
                                else -> if (format in setOf("tx3g", "text")) 17 else 0
                            }
                            if (trackType == 1 || trackType == 2) {
                                val fps = if (trackType == 1 && stts.isNotEmpty() && stts.first().second > 0L) {
                                    timescale.toDouble() / stts.first().second.toDouble()
                                } else 0.0
                                mp4Details[trackId] = TrackDetail(
                                    trackNumber = trackId,
                                    width = vWidth,
                                    height = vHeight,
                                    fps = fps,
                                    sampleRateHz = aSampleRate,
                                    channels = aChannels,
                                )
                            }
                            val isSub = trackType == 17
                            if (isSub && stsz.isNotEmpty() && chunkOffsets.isNotEmpty()) {
                                tracks[trackId] = EmbeddedTrack(
                                    trackNumber = trackId,
                                    type = 17,
                                    codecId = format.ifBlank { "S_TEXT/UTF8" },
                                    name = trackName,
                                    language = lang,
                                )
                                val sampleTimes = ArrayList<Pair<Long, Long>>(stsz.size)
                                var curTick = 0L
                                for ((cnt, dur) in stts) {
                                    for (k in 0 until cnt.toInt()) {
                                        if (sampleTimes.size >= stsz.size) break
                                        val startMs = (curTick * 1000L) / timescale
                                        curTick += dur
                                        val endMs = (curTick * 1000L) / timescale
                                        sampleTimes.add(startMs to endMs)
                                    }
                                }
                                val cueList = mutableListOf<PlaybackRules.SubtitleCue>()
                                var sampleIdx = 0
                                for (cIdx in chunkOffsets.indices) {
                                    var spc = 1
                                    for (entry in stsc) {
                                        if (cIdx + 1 >= entry.first) spc = entry.second else break
                                    }
                                    var offset = chunkOffsets[cIdx]
                                    for (s in 0 until spc) {
                                        if (sampleIdx >= stsz.size) break
                                        val len = stsz[sampleIdx]
                                        val timing = sampleTimes.getOrNull(sampleIdx) ?: (0L to 0L)
                                        sampleIdx++
                                        if (len in 3..65536 && offset + len <= fileLength) {
                                            reader.seek(offset)
                                            val sampleBytes = ByteArray(len)
                                            if (reader.readFully(sampleBytes)) {
                                                val textLen = ((sampleBytes[0].toInt() and 0xFF) shl 8) or (sampleBytes[1].toInt() and 0xFF)
                                                if (textLen > 0 && 2 + textLen <= sampleBytes.size) {
                                                    val txt = String(sampleBytes, 2, textLen, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
                                                    if (txt.isNotBlank()) {
                                                        cueList.add(PlaybackRules.SubtitleCue(timing.first, timing.second.coerceAtLeast(timing.first + 400L), txt))
                                                    }
                                                }
                                            }
                                        }
                                        offset += len
                                    }
                                }
                                if (cueList.isNotEmpty()) {
                                    cues[trackId] = cueList.sortedBy { it.startMs }
                                }
                            }
                        }
                    }
                }
            }
        }
        return DemuxResult(
            tracks = tracks,
            cuesByTrack = cues,
            containerInfo = ContainerInfo(
                format = "MPEG-4 / QuickTime",
                encodingSoftware = mp4EncodingSw,
                durationMs = mp4DurationMs,
                trackDetails = mp4Details,
            ),
        )
    }
}
