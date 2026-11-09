package app.msplayer.offline

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * High-performance buffered container demuxer for Matroska (MKV / WebM) embedded subtitles.
 * Uses a 64 KB read-ahead FileChannel buffer to avoid per-byte FUSE syscalls over Android
 * shared storage, and supports File, FileDescriptor, and content:// URIs cleanly.
 */
object ContainerDemuxer {

    data class EmbeddedTrack(
        val trackNumber: Int,
        val type: Int, // 17 = Subtitle
        val codecId: String,
        val name: String,
        val language: String
    )

    data class DemuxResult(
        val tracks: Map<Int, EmbeddedTrack>,
        val cuesByTrack: Map<Int, List<PlaybackRules.SubtitleCue>>
    )

    private class BufferedChannelReader(private val channel: FileChannel) {
        val fileLength: Long = runCatching { channel.size() }.getOrDefault(0L)
        private val buf = ByteArray(65536)
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

    private fun isTextSubtitleCodec(codecId: String): Boolean {
        val c = codecId.trim()
        if (c.isEmpty()) return true
        return c.startsWith("S_TEXT", ignoreCase = true) ||
            c.contains("WEBVTT", ignoreCase = true) ||
            c.equals("TEXT", ignoreCase = true)
    }

    /** Extracts embedded text subtitle tracks/cues from an MKV/WebM file URI or FD. */
    fun parseMkv(context: Context, uri: Uri): DemuxResult {
        if (uri.scheme == "file") {
            val f = uri.path?.let(::File)
            if (f != null && f.exists()) return parseMkv(f)
        }
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                parseMkv(pfd.fileDescriptor)
            } ?: DemuxResult(emptyMap(), emptyMap())
        }.getOrDefault(DemuxResult(emptyMap(), emptyMap()))
    }

    fun parseMkv(file: File): DemuxResult {
        if (!file.exists() || !file.canRead() || file.length() < 12) {
            return DemuxResult(emptyMap(), emptyMap())
        }
        return runCatching {
            FileInputStream(file).use { fis -> parseChannel(fis.channel) }
        }.getOrDefault(DemuxResult(emptyMap(), emptyMap()))
    }

    fun parseMkv(fd: FileDescriptor): DemuxResult {
        if (!fd.valid()) return DemuxResult(emptyMap(), emptyMap())
        return runCatching {
            val channel = FileInputStream(fd).channel
            channel.position(0)
            parseChannel(channel)
        }.getOrDefault(DemuxResult(emptyMap(), emptyMap()))
    }

    private fun parseChannel(channel: FileChannel): DemuxResult {
        val reader = BufferedChannelReader(channel)
        val fileLength = reader.fileLength
        if (fileLength < 12L) return DemuxResult(emptyMap(), emptyMap())

        val tracks = linkedMapOf<Int, EmbeddedTrack>()
        val cues = linkedMapOf<Int, MutableList<PlaybackRules.SubtitleCue>>()

        var timecodeScale = 1_000_000L // 1 ms default
        var clusterTimecode = 0L

        val topSegmentLevelIds = setOf(
            0x114D9B74L, // SeekHead
            0x1549A966L, // Info
            0x1654AE6BL, // Tracks
            0x1F43B675L, // Cluster
            0x1C53BB6BL, // Cues
            0x1941A469L, // Attachments
            0x1043A770L, // Chapters
            0x1254C367L, // Tags
            0x18538067L, // Segment
            0x1A45DFA3L, // EBML Header
        )

        while (reader.position() < fileLength) {
            val eid = reader.readId() ?: break
            val size = reader.readVintSize() ?: break
            val pos = reader.position()

            when (eid) {
                0x18538067L -> {
                    // Segment container: descend directly
                    continue
                }
                0x1549A966L -> {
                    // Info
                    val infoEnd = reader.elementEnd(pos, size)
                    while (reader.position() < infoEnd) {
                        val iid = reader.readId() ?: break
                        val isize = reader.readVintSize() ?: break
                        val ipos = reader.position()
                        if (iid == 0x2AD7B1L && isize in 1..8) {
                            val ts = reader.readUInt(isize.toInt())
                            if (ts > 0) timecodeScale = ts
                        } else {
                            reader.seek(reader.elementEnd(ipos, isize))
                        }
                    }
                    reader.seek(infoEnd)
                }
                0x1654AE6BL -> {
                    // Tracks
                    val tracksEnd = reader.elementEnd(pos, size)
                    while (reader.position() < tracksEnd) {
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

                            while (reader.position() < teEnd) {
                                val fid = reader.readId() ?: break
                                val fsize = reader.readVintSize() ?: break
                                val fpos = reader.position()
                                val fEnd = reader.elementEnd(fpos, fsize)
                                when (fid) {
                                    0xD7L -> if (fsize in 1..4) tNum = reader.readUInt(fsize.toInt()).toInt()
                                    0x83L -> if (fsize in 1..4) tType = reader.readUInt(fsize.toInt()).toInt()
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
                                }
                                reader.seek(fEnd)
                            }
                            if (tNum != null && tType != null) {
                                tracks[tNum] = EmbeddedTrack(tNum, tType, tCodec, tName, tLang)
                                if (tType == 17 && isTextSubtitleCodec(tCodec)) {
                                    cues[tNum] = mutableListOf()
                                }
                            }
                        }
                        reader.seek(teEnd)
                    }
                    reader.seek(tracksEnd)
                }
                0x1F43B675L -> {
                    // Cluster (early stop if there are NO text subtitle tracks to parse!)
                    if (cues.isEmpty()) {
                        if (size < 0L) break // unknown size cluster and zero text subtitle tracks -> done!
                        reader.seek(reader.elementEnd(pos, size))
                        continue
                    }
                    val clusterEnd = reader.elementEnd(pos, size)
                    while (reader.position() < clusterEnd) {
                        val elemStart = reader.position()
                        val cid = reader.readId() ?: break
                        if (size < 0L && cid in topSegmentLevelIds) {
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
                                    val relTime = reader.readShortSigned()
                                    reader.readByte() // flags
                                    val headerLen = (reader.position() - cpos).toInt()
                                    val payloadLen = (csize - headerLen).toInt()
                                    if (payloadLen in 1..262144 && tNum in cues) {
                                        val payload = ByteArray(payloadLen)
                                        if (reader.readFully(payload)) {
                                            val startMs = ((clusterTimecode + relTime) * (timecodeScale / 1_000_000.0)).toLong()
                                            val rawText = String(payload, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
                                            val cleanText = sanitizeSubtitle(rawText, tracks[tNum]?.codecId ?: "")
                                            if (cleanText.isNotBlank()) {
                                                cues[tNum]?.add(PlaybackRules.SubtitleCue(startMs, startMs + 3000L, cleanText))
                                            }
                                        }
                                    }
                                }
                            }
                            0xA0L -> { // BlockGroup
                                var bgBlock: Triple<Int, Int, ByteArray>? = null
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
                                                val relTime = reader.readShortSigned()
                                                reader.readByte() // flags
                                                val hLen = (reader.position() - bgPos).toInt()
                                                val pLen = (bgSize - hLen).toInt()
                                                if (pLen in 1..262144 && tNum in cues) {
                                                    val payload = ByteArray(pLen)
                                                    if (reader.readFully(payload)) {
                                                        bgBlock = Triple(tNum, relTime, payload)
                                                    }
                                                }
                                            }
                                        }
                                        0x9BL -> { // BlockDuration
                                            if (bgSize in 1..8) bgDur = reader.readUInt(bgSize.toInt())
                                        }
                                    }
                                    reader.seek(bgEnd)
                                }
                                if (bgBlock != null) {
                                    val (tNum, relTime, payload) = bgBlock
                                    val startMs = ((clusterTimecode + relTime) * (timecodeScale / 1_000_000.0)).toLong()
                                    val endMs = startMs + (bgDur * (timecodeScale / 1_000_000.0)).toLong()
                                    val rawText = String(payload, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
                                    val cleanText = sanitizeSubtitle(rawText, tracks[tNum]?.codecId ?: "")
                                    if (cleanText.isNotBlank()) {
                                        cues[tNum]?.add(PlaybackRules.SubtitleCue(startMs, endMs.coerceAtLeast(startMs + 400L), cleanText))
                                    }
                                }
                            }
                        }
                        reader.seek(cend)
                    }
                    reader.seek(clusterEnd)
                }
                else -> {
                    if (size < 0L) break
                    reader.seek(reader.elementEnd(pos, size))
                }
            }
        }

        val finalCues = cues.mapValues { (_, list) -> list.sortedBy { it.startMs } }
        return DemuxResult(tracks, finalCues)
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
}
