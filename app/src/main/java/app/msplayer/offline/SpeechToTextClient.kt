package app.msplayer.offline

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * AI Subtitles Speech-to-Text Client & Compression Pipeline.
 * Fast-splits compact AAC/Opus audio streams without re-encoding, compresses heavy/multi-channel
 * codecs into lightweight 16 kHz / 18 kbps Mono AAC, and pipelines parallel chunk uploads with
 * progressive cues, multi-key rotation, and automatic backup failover.
 */
object SpeechToTextClient {

    const val PROVIDER_GROQ = "groq"
    const val PROVIDER_DEEPGRAM = "deepgram"

    val DEFAULT_GROQ_KEY: String get() = BuildConfig.DEFAULT_GROQ_KEY
    val DEFAULT_DEEPGRAM_KEY: String get() = BuildConfig.DEFAULT_DEEPGRAM_KEY
    val DEFAULT_CLOUDFLARE_TOKEN: String get() = BuildConfig.DEFAULT_CLOUDFLARE_TOKEN

    const val GROQ_ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
    const val GROQ_TRANSLATIONS_ENDPOINT = "https://api.groq.com/openai/v1/audio/translations"
    const val DEEPGRAM_ENDPOINT = "https://api.deepgram.com/v1/listen?model=nova-2&smart_format=true"

    // Keep chunks compact (<= 10 MB / <= 8 min, 4 min first chunk for fast live preview)
    const val MAX_GROQ_CHUNK_BYTES = 10L * 1024L * 1024L
    const val FIRST_CHUNK_DURATION_US = 4L * 60L * 1_000_000L
    const val MAX_CHUNK_DURATION_US = 8L * 60L * 1_000_000L
    const val MAX_DIRECT_MUX_BITRATE_BPS = 220_000L
    const val TARGET_SPEECH_BITRATE = 18_000

    data class ExtractedChunk(
        val file: File,
        val startMs: Long,
        val mime: String
    )

    data class TranscriptionResult(
        val words: List<AutoSubEngine.WordInfo>,
        val provider: String,
        val groqError: String? = null
    )

    fun parseApiKeys(raw: String): List<String> =
        raw.split(',', ';', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun activeGroqKey(context: Context): String =
        Store.prefs(context).getString("groq_api_key", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_GROQ_KEY

    fun activeDeepgramKey(context: Context): String =
        Store.prefs(context).getString("deepgram_api_key", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_DEEPGRAM_KEY

    fun generateMultipartBoundary(seed: Long = System.currentTimeMillis()): String =
        "MSPlayerBoundary$seed"

    fun isEnglishLanguageTag(language: String?): Boolean {
        val clean = language?.trim()?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_') ?: return false
        return clean == "en" || clean == "eng" || clean == "english"
    }

    fun shouldDirectMuxSplit(
        audioMime: String,
        directSupported: Boolean,
        measuredBitrateBps: Long,
        estimatedAudioBytes: Long,
    ): Boolean {
        if (!directSupported) return false
        val lower = audioMime.lowercase(Locale.ROOT)
        val compactCodec = lower.contains("aac") ||
            lower.contains("mp4a") ||
            lower.contains("opus") ||
            lower.contains("vorbis") ||
            lower == "audio/mpeg"
        if (!compactCodec) return false
        if (measuredBitrateBps in 1L..MAX_DIRECT_MUX_BITRATE_BPS) return true
        return measuredBitrateBps <= 0L && estimatedAudioBytes in 1L..(15L * 1024L * 1024L)
    }

    fun decimationFactorForSampleRate(rateHz: Int): Int = when {
        rateHz >= 88200 -> 6
        rateHz >= 64000 -> 4
        rateHz == 48000 -> 3
        rateHz >= 32000 -> 2
        else -> 1
    }

    fun chunkLimitDurationUs(chunkIndex: Int, totalDurationUs: Long): Long =
        if (chunkIndex == 0 && totalDurationUs > FIRST_CHUNK_DURATION_US + 90_000_000L) {
            FIRST_CHUNK_DURATION_US
        } else {
            MAX_CHUNK_DURATION_US
        }

    private fun estimateSourceSizeBytes(context: Context, uri: Uri): Long {
        if (uri.scheme == "file") {
            uri.path?.let { return File(it).length() }
        }
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }.getOrDefault(-1L)
    }

    private fun probeTrackBitrateBps(extractor: MediaExtractor, fallbackHeaderBitrate: Long): Long {
        if (fallbackHeaderBitrate > 0L) return fallbackHeaderBitrate
        return try {
            val probeBuf = ByteBuffer.allocate(64 * 1024)
            var totalBytes = 0L
            var firstUs = -1L
            var lastUs = -1L
            var count = 0
            while (count < 48) {
                val sz = extractor.readSampleData(probeBuf, 0)
                val ts = extractor.sampleTime
                if (sz < 0 || ts < 0) break
                if (firstUs < 0L) firstUs = ts
                lastUs = ts
                totalBytes += sz
                count++
                if (!extractor.advance()) break
                if (lastUs - firstUs >= 2_000_000L && count >= 12) break
            }
            extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val spanUs = (lastUs - firstUs).coerceAtLeast(0L)
            if (count >= 8 && spanUs >= 250_000L) {
                ((totalBytes * 8.0 * 1_000_000.0) / spanUs).toLong()
            } else {
                -1L
            }
        } catch (_: Exception) {
            runCatching { extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC) }
            -1L
        }
    }

    /**
     * Extracts audio track (preferring English audio first on dual-audio containers) into
     * compact chunks (<= 10 MB / <= 8 min, using direct zero-reencode stream copy when
     * already AAC/Opus <= 220 kbps, or fast 16 kHz / 18 kbps Mono AAC-LC compression).
     */
    fun extractAudioChunks(
        context: Context,
        uri: Uri,
        cacheDir: File,
        maxChunkBytes: Long = MAX_GROQ_CHUNK_BYTES,
        preferredAudioOrdinal: Int = -1,
        preferEnglishFirst: Boolean = true,
        onProgress: ((Int) -> Unit)? = null
    ): List<ExtractedChunk> = extractAudioChunksInternal(
        context = context,
        uri = uri,
        cacheDir = cacheDir,
        maxChunkBytes = maxChunkBytes,
        preferredAudioOrdinal = preferredAudioOrdinal,
        preferEnglishFirst = preferEnglishFirst,
        onProgress = onProgress,
        onChunkReady = null,
        isCancelled = null,
    )

    private fun extractAudioChunksInternal(
        context: Context,
        uri: Uri,
        cacheDir: File,
        maxChunkBytes: Long = MAX_GROQ_CHUNK_BYTES,
        preferredAudioOrdinal: Int = -1,
        preferEnglishFirst: Boolean = true,
        onProgress: ((Int) -> Unit)? = null,
        onChunkReady: ((Int, ExtractedChunk) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null,
    ): List<ExtractedChunk> {
        cacheDir.listFiles { f -> f.name.startsWith("temp_autosub_") }?.forEach { it.delete() }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            data class AudioCandidate(
                val ordinal: Int,
                val trackIndex: Int,
                val format: MediaFormat,
                val mime: String,
                val language: String?,
            )
            val candidates = mutableListOf<AudioCandidate>()
            var ordinal = 0
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    val lang = if (format.containsKey(MediaFormat.KEY_LANGUAGE)) format.getString(MediaFormat.KEY_LANGUAGE) else null
                    candidates += AudioCandidate(ordinal++, i, format, mime, lang)
                }
            }
            if (candidates.isEmpty()) {
                Log.i("MSPlayer", "AI Sub extractor found 0 supported audio tracks; falling back")
                return emptyList()
            }

            val englishCandidate = if (preferEnglishFirst) {
                if (preferredAudioOrdinal >= 0) {
                    candidates.firstOrNull { it.ordinal == preferredAudioOrdinal && isEnglishLanguageTag(it.language) }
                        ?: candidates.firstOrNull { isEnglishLanguageTag(it.language) }
                } else {
                    candidates.firstOrNull { isEnglishLanguageTag(it.language) }
                }
            } else null

            val chosen = englishCandidate
                ?: candidates.firstOrNull { it.ordinal == preferredAudioOrdinal }
                ?: candidates.first()

            val audioTrackIndex = chosen.trackIndex
            val audioFormat = chosen.format
            val audioMime = chosen.mime

            val totalDurationUs = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                audioFormat.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            val headerBitrate = if (audioFormat.containsKey(MediaFormat.KEY_BIT_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
            } else 0L
            val estimatedAudioBytes = when {
                headerBitrate > 0L && totalDurationUs > 0L -> (headerBitrate * (totalDurationUs / 1_000_000L)) / 8L
                else -> estimateSourceSizeBytes(context, uri)
            }

            extractor.selectTrack(audioTrackIndex)

            val (outputFormat, ext, contentMime) = when {
                audioMime.contains("aac", true) || audioMime.contains("mp4a", true) ->
                    Triple(MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "m4a", "audio/m4a")
                audioMime.contains("opus", true) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    Triple(MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG, "ogg", "audio/ogg")
                (audioMime.contains("opus", true) || audioMime.contains("vorbis", true)) ->
                    Triple(MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM, "webm", "audio/webm")
                else ->
                    Triple(MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "m4a", "audio/m4a")
            }

            val directSupported = runCatching {
                val probeFile = File(cacheDir, "temp_autosub_probe.$ext")
                var probeMuxer: MediaMuxer? = null
                try {
                    probeMuxer = MediaMuxer(probeFile.absolutePath, outputFormat)
                    probeMuxer.addTrack(audioFormat)
                    true
                } finally {
                    try { probeMuxer?.release() } catch (_: Exception) {}
                    probeFile.delete()
                }
            }.getOrDefault(false)

            val lowerMime = audioMime.lowercase(Locale.ROOT)
            val isCompactCandidate = lowerMime.contains("aac") ||
                lowerMime.contains("mp4a") ||
                lowerMime.contains("opus") ||
                lowerMime.contains("vorbis") ||
                lowerMime == "audio/mpeg"
            val measuredBitrateBps = if (directSupported && isCompactCandidate) {
                probeTrackBitrateBps(extractor, headerBitrate)
            } else headerBitrate

            val useDirectSplit = shouldDirectMuxSplit(
                audioMime = audioMime,
                directSupported = directSupported,
                measuredBitrateBps = measuredBitrateBps,
                estimatedAudioBytes = estimatedAudioBytes,
            )
            val extractT0 = System.currentTimeMillis()

            if (useDirectSplit) {
                val directChunks = runCatching {
                    val chunks = mutableListOf<ExtractedChunk>()
                    val maxBufferSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(256 * 1024)
                    } else 512 * 1024
                    val buffer = ByteBuffer.allocate(maxBufferSize)
                    val bufferInfo = MediaCodec.BufferInfo()

                    var chunkIndex = 0
                    var muxer: MediaMuxer? = null
                    var muxerTrackIndex = -1
                    var currentFile: File? = null
                    var chunkStartUs = -1L
                    var chunkBytes = 0L
                    var lastProgressPct = -1

                    fun closeCurrentChunk() {
                        try { muxer?.stop() } catch (_: Exception) {}
                        try { muxer?.release() } catch (_: Exception) {}
                        muxer = null
                        val f = currentFile
                        if (f != null && f.exists() && f.length() > 1024L && chunkStartUs >= 0L) {
                            val built = ExtractedChunk(f, chunkStartUs / 1000L, contentMime)
                            val idx = chunks.size
                            chunks.add(built)
                            onChunkReady?.invoke(idx, built)
                        } else {
                            f?.delete()
                        }
                        currentFile = null
                        chunkStartUs = -1L
                        chunkBytes = 0L
                    }

                    try {
                        while (true) {
                            if (isCancelled?.invoke() == true) break
                            bufferInfo.offset = 0
                            bufferInfo.size = extractor.readSampleData(buffer, 0)
                            if (bufferInfo.size < 0) break

                            val sampleUs = extractor.sampleTime.coerceAtLeast(0L)
                            if (muxer == null) {
                                val f = File(cacheDir, "temp_autosub_${chunkIndex++}.$ext")
                                val m = MediaMuxer(f.absolutePath, outputFormat)
                                muxerTrackIndex = m.addTrack(audioFormat)
                                m.start()
                                muxer = m
                                currentFile = f
                                chunkStartUs = sampleUs
                                chunkBytes = 0L
                            }

                            bufferInfo.presentationTimeUs = (sampleUs - chunkStartUs).coerceAtLeast(0L)
                            bufferInfo.flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                                MediaCodec.BUFFER_FLAG_KEY_FRAME
                            } else 0
                            muxer!!.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                            chunkBytes += bufferInfo.size + 32L

                            if (totalDurationUs > 0L) {
                                val p = ((sampleUs.toDouble() / totalDurationUs) * 40.0).toInt().coerceIn(0, 40)
                                if (p != lastProgressPct) {
                                    lastProgressPct = p
                                    onProgress?.invoke(p)
                                }
                            }

                            extractor.advance()

                            val limitUs = chunkLimitDurationUs(chunks.size, totalDurationUs)
                            if (chunkBytes >= maxChunkBytes || (sampleUs - chunkStartUs) >= limitUs) {
                                closeCurrentChunk()
                            }
                        }
                        closeCurrentChunk()
                    } finally {
                        try { muxer?.release() } catch (_: Exception) {}
                    }
                    chunks
                }.getOrElse { emptyList() }

                if (directChunks.isNotEmpty()) {
                    Log.i(
                        "MSPlayer",
                        "AI Sub direct-mux split ${directChunks.size} chunks (${directChunks.sumOf { it.file.length() } / 1024} KB) in ${System.currentTimeMillis() - extractT0} ms"
                    )
                    onProgress?.invoke(40)
                    return directChunks
                }
                if (isCancelled?.invoke() == true) return emptyList()
                directChunks.forEach { it.file.delete() }
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            val encodedChunks = transcodeAudioToAacChunks(
                extractor = extractor,
                inputFormat = audioFormat,
                totalDurationUs = totalDurationUs,
                cacheDir = cacheDir,
                maxChunkBytes = maxChunkBytes,
                onProgress = onProgress,
                onChunkReady = onChunkReady,
                isCancelled = isCancelled,
            )
            Log.i(
                "MSPlayer",
                "AI Sub 16k/18k AAC transcode ${encodedChunks.size} chunks (${encodedChunks.sumOf { it.file.length() } / 1024} KB) in ${System.currentTimeMillis() - extractT0} ms"
            )
            return encodedChunks
        } catch (_: Exception) {
            return emptyList()
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /**
     * Streaming hardware speech compressor: decodes any multi-channel/lossless/high-bitrate audio
     * (AC-3/EAC-3/DTS 5.1, FLAC, high-bitrate stereo, multi-GB sources) with bulk PCM downmix/decimation
     * into lightweight 16 kHz / 18 kbps Mono AAC-LC `.m4a` chunks (~135 KB/min) and emits chunks
     * as soon as each finishes so transcription can start in parallel immediately.
     */
    private fun transcodeAudioToAacChunks(
        extractor: MediaExtractor,
        inputFormat: MediaFormat,
        totalDurationUs: Long,
        cacheDir: File,
        maxChunkBytes: Long,
        onProgress: ((Int) -> Unit)?,
        onChunkReady: ((Int, ExtractedChunk) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null,
    ): List<ExtractedChunk> {
        val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return emptyList()
        var decodedChannels = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        } else 2
        var decodedRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
        } else 44100

        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        val chunks = mutableListOf<ExtractedChunk>()

        class PcmFifo {
            private var buf = ShortArray(65536)
            private var head = 0
            private var tail = 0
            val available: Int get() = tail - head

            fun pushArray(src: ShortArray, count: Int) {
                if (count <= 0) return
                if (tail + count > buf.size) {
                    val active = tail - head
                    if (active + count <= buf.size) {
                        System.arraycopy(buf, head, buf, 0, active)
                        head = 0
                        tail = active
                    } else {
                        var newCap = (buf.size * 2).coerceAtLeast(active + count + 16384)
                        val next = ShortArray(newCap)
                        if (active > 0) System.arraycopy(buf, head, next, 0, active)
                        buf = next
                        head = 0
                        tail = active
                    }
                }
                System.arraycopy(src, 0, buf, tail, count)
                tail += count
            }

            fun pop(maxCount: Int): ShortArray {
                val n = maxCount.coerceAtMost(available)
                val out = ShortArray(n)
                System.arraycopy(buf, head, out, 0, n)
                head += n
                if (head == tail) {
                    head = 0
                    tail = 0
                }
                return out
            }
        }

        try {
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            var decimationFactor = 1
            var targetSampleRate = decodedRate
            var encoderStartPtsUs = -1L
            var encodedSampleCount = 0L

            fun ensureEncoderConfigured(rateHz: Int) {
                if (encoder != null) return
                decimationFactor = decimationFactorForSampleRate(rateHz)
                val candidateRate = (rateHz / decimationFactor).coerceIn(16000, 48000)
                val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                try {
                    val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, candidateRate, 1).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, TARGET_SPEECH_BITRATE)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                    }
                    enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    targetSampleRate = candidateRate
                } catch (_: Exception) {
                    decimationFactor = 1
                    targetSampleRate = rateHz.coerceIn(16000, 48000)
                    val fallbackFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, targetSampleRate, 1).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, 24_000)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                    }
                    enc.configure(fallbackFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
                enc.start()
                encoder = enc
            }

            val pcmFifo = PcmFifo()
            var decimAccum = 0L
            var decimPhase = 0

            var decoderInputDone = false
            var decoderOutputDone = false
            var encoderEosQueued = false
            var encoderDone = false
            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()

            var muxerTrack = -1
            var currentFile: File? = null
            var chunkIndex = 0
            var chunkStartUs = -1L
            var chunkBytes = 0L
            var lastProgressPct = -1

            fun closeChunk() {
                try { muxer?.stop() } catch (_: Exception) {}
                try { muxer?.release() } catch (_: Exception) {}
                muxer = null
                val f = currentFile
                if (f != null && f.exists() && f.length() > 1024L && chunkStartUs >= 0L) {
                    val built = ExtractedChunk(f, chunkStartUs / 1000L, "audio/m4a")
                    val idx = chunks.size
                    chunks.add(built)
                    onChunkReady?.invoke(idx, built)
                } else {
                    f?.delete()
                }
                currentFile = null
                chunkStartUs = -1L
                chunkBytes = 0L
            }

            var pcmScratch = ShortArray(8192)
            var monoScratch = ShortArray(8192)
            while (!encoderDone) {
                if (isCancelled?.invoke() == true) {
                    currentFile?.delete()
                    return emptyList()
                }
                var didWork = false
                while (!decoderInputDone && pcmFifo.available < 49152) {
                    val inIdx = decoder.dequeueInputBuffer(0)
                    if (inIdx < 0) break
                    didWork = true
                    val buf = decoder.getInputBuffer(inIdx)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        decoderInputDone = true
                        break
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)
                        extractor.advance()
                    }
                }

                while (!decoderOutputDone && pcmFifo.available < 65536) {
                    val outIdx = decoder.dequeueOutputBuffer(decInfo, 0)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            didWork = true
                            val df = decoder.outputFormat
                            if (df.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                decodedChannels = df.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                            }
                            if (df.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                decodedRate = df.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
                            }
                            ensureEncoderConfigured(decodedRate)
                        }
                        outIdx >= 0 -> {
                            didWork = true
                            ensureEncoderConfigured(decodedRate)
                            val decBuf = decoder.getOutputBuffer(outIdx)!!
                            if (decInfo.size > 0) {
                                if (encoderStartPtsUs < 0L) {
                                    encoderStartPtsUs = decInfo.presentationTimeUs.coerceAtLeast(0L)
                                }
                                decBuf.position(decInfo.offset)
                                decBuf.limit(decInfo.offset + decInfo.size)
                                val totalShorts = decInfo.size / 2
                                if (pcmScratch.size < totalShorts) pcmScratch = ShortArray(totalShorts)
                                decBuf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcmScratch, 0, totalShorts)
                                val frames = totalShorts / decodedChannels
                                if (monoScratch.size < frames) monoScratch = ShortArray(frames)

                                if (decimationFactor == 1 && decodedChannels == 1) {
                                    pcmFifo.pushArray(pcmScratch, frames)
                                } else {
                                    var outCount = 0
                                    for (f in 0 until frames) {
                                        val base = f * decodedChannels
                                        val mono = if (decodedChannels == 1) {
                                            pcmScratch[base].toInt()
                                        } else {
                                            var sum = 0
                                            for (c in 0 until decodedChannels) {
                                                sum += pcmScratch[base + c].toInt()
                                            }
                                            sum / decodedChannels
                                        }
                                        if (decimationFactor == 1) {
                                            monoScratch[outCount++] = mono.coerceIn(-32768, 32767).toShort()
                                        } else {
                                            decimAccum += mono
                                            decimPhase++
                                            if (decimPhase >= decimationFactor) {
                                                monoScratch[outCount++] = (decimAccum / decimationFactor).toInt().coerceIn(-32768, 32767).toShort()
                                                decimAccum = 0L
                                                decimPhase = 0
                                            }
                                        }
                                    }
                                    if (outCount > 0) pcmFifo.pushArray(monoScratch, outCount)
                                }
                            }
                            decoder.releaseOutputBuffer(outIdx, false)
                            if ((decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                if (encoderStartPtsUs < 0L) encoderStartPtsUs = 0L
                                decoderOutputDone = true
                            }
                        }
                        else -> break
                    }
                }

                val enc = encoder
                if (enc != null) {
                    while (!encoderEosQueued && (pcmFifo.available > 0 || decoderOutputDone)) {
                        val encInIdx = enc.dequeueInputBuffer(0)
                        if (encInIdx < 0) break
                        didWork = true
                        val encInBuf = enc.getInputBuffer(encInIdx)!!
                        encInBuf.clear()
                        if (pcmFifo.available > 0) {
                            val maxSamples = (encInBuf.remaining() / 2).coerceAtLeast(1)
                            val samples = pcmFifo.pop(maxSamples)
                            encInBuf.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
                            val ptsUs = (encoderStartPtsUs.coerceAtLeast(0L)) +
                                (encodedSampleCount * 1_000_000L) / targetSampleRate
                            encodedSampleCount += samples.size
                            enc.queueInputBuffer(encInIdx, 0, samples.size * 2, ptsUs, 0)
                        } else if (decoderOutputDone) {
                            val ptsUs = (encoderStartPtsUs.coerceAtLeast(0L)) +
                                (encodedSampleCount * 1_000_000L) / targetSampleRate
                            enc.queueInputBuffer(encInIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            encoderEosQueued = true
                        }
                    }

                    while (true) {
                        val encOutIdx = enc.dequeueOutputBuffer(encInfo, 0)
                        if (encOutIdx == MediaCodec.INFO_TRY_AGAIN_LATER || encOutIdx < 0) {
                            if (encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                                didWork = true
                                continue
                            }
                            break
                        }
                        didWork = true
                        val outBuf = enc.getOutputBuffer(encOutIdx)!!
                        if ((encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && encInfo.size > 0) {
                            val samplePts = encInfo.presentationTimeUs.coerceAtLeast(0L)
                            val limitUs = chunkLimitDurationUs(chunks.size, totalDurationUs)
                            if (muxer != null && (chunkBytes >= maxChunkBytes || (samplePts - chunkStartUs) >= limitUs)) {
                                closeChunk()
                            }
                            if (muxer == null) {
                                val f = File(cacheDir, "temp_autosub_${chunkIndex++}.m4a")
                                val m = MediaMuxer(f.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                                muxerTrack = m.addTrack(enc.outputFormat)
                                m.start()
                                muxer = m
                                currentFile = f
                                chunkStartUs = samplePts
                                chunkBytes = 0L
                            }
                            outBuf.position(encInfo.offset)
                            outBuf.limit(encInfo.offset + encInfo.size)
                            val relInfo = MediaCodec.BufferInfo().apply {
                                offset = encInfo.offset
                                size = encInfo.size
                                presentationTimeUs = (samplePts - chunkStartUs).coerceAtLeast(0L)
                                flags = encInfo.flags
                            }
                            muxer!!.writeSampleData(muxerTrack, outBuf, relInfo)
                            chunkBytes += encInfo.size + 32L
                            if (totalDurationUs > 0L) {
                                val p = ((samplePts.toDouble() / totalDurationUs) * 40.0).toInt().coerceIn(0, 40)
                                if (p != lastProgressPct) {
                                    lastProgressPct = p
                                    onProgress?.invoke(p)
                                }
                            }
                        }
                        enc.releaseOutputBuffer(encOutIdx, false)
                        if ((encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            encoderDone = true
                            break
                        }
                    }
                } else if (decoderOutputDone) {
                    encoderDone = true
                }
                if (!didWork && !encoderDone) {
                    Thread.sleep(1)
                }
            }
            closeChunk()
            onProgress?.invoke(40)
            return chunks
        } catch (_: Exception) {
            return emptyList()
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) {}
            try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Single-file helper preserved for compatibility.
     */
    fun extractAudio(
        context: Context,
        uri: Uri,
        outputFile: File,
        onProgress: ((Int) -> Unit)? = null
    ): Boolean {
        val dir = outputFile.parentFile ?: context.cacheDir
        val chunks = extractAudioChunks(context, uri, dir, Long.MAX_VALUE, -1, true, onProgress)
        val first = chunks.firstOrNull() ?: return false
        first.file.copyTo(outputFile, overwrite = true)
        chunks.forEach { it.file.delete() }
        return true
    }

    /**
     * Sends a compressed audio chunk (<= 25 MB) to primary speech recognition endpoint
     * over fixed-length buffered RFC-2046 multipart streaming.
     */
    fun transcribeWithGroq(
        audioFile: File,
        apiKey: String,
        onProgress: ((Int) -> Unit)? = null,
        translateToEnglish: Boolean = false,
        languageHint: String? = null,
    ): List<AutoSubEngine.WordInfo> {
        val boundary = generateMultipartBoundary()
        val lineEnd = "\r\n"
        val twoHyphens = "--"
        val mime = mimeForExtension(audioFile.extension)
        val modelName = if (translateToEnglish) "whisper-large-v3" else "whisper-large-v3-turbo"
        val endpoint = if (translateToEnglish) GROQ_TRANSLATIONS_ENDPOINT else GROQ_ENDPOINT
        val cleanHint = languageHint?.trim()?.lowercase(Locale.ROOT)
            ?.replace(Regex("""[^a-z-]"""), "")
            ?.takeIf { it.isNotBlank() && it != "auto" && it != "all" }
        val preamble = buildString {
            append("$twoHyphens$boundary$lineEnd")
            append("Content-Disposition: form-data; name=\"model\"$lineEnd$lineEnd")
            append("$modelName$lineEnd")

            append("$twoHyphens$boundary$lineEnd")
            append("Content-Disposition: form-data; name=\"response_format\"$lineEnd$lineEnd")
            append("verbose_json$lineEnd")

            if (!translateToEnglish) {
                append("$twoHyphens$boundary$lineEnd")
                append("Content-Disposition: form-data; name=\"timestamp_granularities[]\"$lineEnd$lineEnd")
                append("word$lineEnd")
                if (cleanHint != null) {
                    append("$twoHyphens$boundary$lineEnd")
                    append("Content-Disposition: form-data; name=\"language\"$lineEnd$lineEnd")
                    append("$cleanHint$lineEnd")
                }
            }

            append("$twoHyphens$boundary$lineEnd")
            append("Content-Disposition: form-data; name=\"temperature\"$lineEnd$lineEnd")
            append("0$lineEnd")

            append("$twoHyphens$boundary$lineEnd")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"${audioFile.name}\"$lineEnd")
            append("Content-Type: $mime$lineEnd$lineEnd")
        }.toByteArray(Charsets.UTF_8)
        val epilogue = "$lineEnd$twoHyphens$boundary$twoHyphens$lineEnd".toByteArray(Charsets.UTF_8)
        val fileBytes = audioFile.length().coerceAtLeast(0L)
        val totalRequestBytes = preamble.size.toLong() + fileBytes + epilogue.size.toLong()

        val url = URL(endpoint)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doInput = true
        conn.doOutput = true
        conn.useCaches = false
        conn.setFixedLengthStreamingMode(totalRequestBytes)
        conn.connectTimeout = 45000
        conn.readTimeout = 180000
        conn.setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 MSPLAYER/0.6.2")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

        val totalForProgress = fileBytes.coerceAtLeast(1L)
        var uploadedBytes = 0L

        BufferedOutputStream(conn.outputStream, 64 * 1024).use { bos ->
            bos.write(preamble)
            FileInputStream(audioFile).use { fis ->
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    bos.write(buffer, 0, bytesRead)
                    uploadedBytes += bytesRead
                    val p = 45 + ((uploadedBytes.toDouble() / totalForProgress) * 35.0).toInt().coerceIn(0, 35)
                    onProgress?.invoke(p)
                }
            }
            bos.write(epilogue)
            bos.flush()
        }

        onProgress?.invoke(83)
        val code = conn.responseCode
        onProgress?.invoke(88)
        if (code in 200..299) {
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            return parseVerboseJson(responseText)
        } else {
            val errorText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
            throw RuntimeException("Speech service HTTP $code: $errorText")
        }
    }

    /**
     * Sends audio to backup speech-to-text endpoint for word-level transcription.
     */
    fun transcribeWithDeepgram(
        audioFile: File,
        apiKey: String = DEFAULT_DEEPGRAM_KEY,
        onProgress: ((Int) -> Unit)? = null
    ): List<AutoSubEngine.WordInfo> {
        val url = URL("$DEEPGRAM_ENDPOINT&punctuate=true&utterances=true")
        val fileBytes = audioFile.length().coerceAtLeast(0L)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doInput = true
        conn.doOutput = true
        conn.useCaches = false
        conn.setFixedLengthStreamingMode(fileBytes)
        conn.connectTimeout = 45000
        conn.readTimeout = 180000
        conn.setRequestProperty("Authorization", "Token ${apiKey.trim()}")
        conn.setRequestProperty("Content-Type", mimeForExtension(audioFile.extension))
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 MSPLAYER/0.6.2")

        val totalForProgress = fileBytes.coerceAtLeast(1L)
        var uploadedBytes = 0L

        BufferedOutputStream(conn.outputStream, 64 * 1024).use { bos ->
            FileInputStream(audioFile).use { fis ->
                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    bos.write(buffer, 0, bytesRead)
                    uploadedBytes += bytesRead
                    val p = 45 + ((uploadedBytes.toDouble() / totalForProgress) * 35.0).toInt().coerceIn(0, 35)
                    onProgress?.invoke(p)
                }
            }
            bos.flush()
        }

        onProgress?.invoke(83)
        val code = conn.responseCode
        onProgress?.invoke(88)
        if (code in 200..299) {
            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            return parseVerboseJson(responseText)
        } else {
            val errorText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
            throw RuntimeException("Backup speech service HTTP $code: $errorText")
        }
    }

    private fun mimeForExtension(ext: String): String = when (ext.lowercase(Locale.ROOT)) {
        "m4a", "mp4" -> "audio/m4a"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "webm" -> "audio/webm"
        "mp3" -> "audio/mpeg"
        "mkv" -> "video/x-matroska"
        else -> "application/octet-stream"
    }

    private fun isAuthFailure(message: String?): Boolean {
        val m = message?.lowercase(Locale.ROOT).orEmpty()
        return m.contains("http 401") || m.contains("http 403") || m.contains("invalid_api_key")
    }

    fun sanitizeErrorMessage(raw: String?): String {
        val msg = raw?.trim().orEmpty()
        if (msg.isEmpty()) return "AI Subtitles transcription failed"
        val lower = msg.lowercase(Locale.ROOT)
        if (
            lower.contains("unable to resolve host") ||
            lower.contains("unknownhostexception") ||
            lower.contains("no address associated with hostname") ||
            lower.contains("failed to connect") ||
            lower.contains("network is unreachable")
        ) {
            return "Unable to reach AI Subtitles service — check your internet connection"
        }
        return msg
            .replace(Regex("""api\.groq\.com|api\.deepgram\.com""", RegexOption.IGNORE_CASE), "AI Subtitles service")
            .replace(Regex("""\bgroq(\s+cloud)?\b|\bdeepgram\b""", RegexOption.IGNORE_CASE), "AI Subtitles")
    }

    private fun isRateLimit(message: String?): Boolean =
        message?.contains("HTTP 429", ignoreCase = true) == true

    private fun transcribeSingleChunkCascade(
        chunk: ExtractedChunk,
        chunkIndex: Int,
        primaryKeys: List<String>,
        backupKeys: List<String>,
        disabledPrimaryKeys: MutableSet<String>,
        disabledBackupKeys: MutableSet<String>,
        hadBackupFallback: AtomicBoolean,
        lastErrorRef: AtomicReference<String?>,
        translateToEnglish: Boolean = false,
        languageHint: String? = null,
    ): List<AutoSubEngine.WordInfo> {
        var lastErr: String? = null
        val candidatePrimary = primaryKeys.filter { it !in disabledPrimaryKeys }
        if (candidatePrimary.isNotEmpty()) {
            val startIdx = chunkIndex % candidatePrimary.size
            for (offset in candidatePrimary.indices) {
                val key = candidatePrimary[(startIdx + offset) % candidatePrimary.size]
                if (key in disabledPrimaryKeys) continue
                try {
                    return transcribeWithGroq(
                        audioFile = chunk.file,
                        apiKey = key,
                        translateToEnglish = translateToEnglish,
                        languageHint = languageHint,
                    )
                } catch (e: Exception) {
                    val msg = e.message ?: e::class.java.simpleName
                    lastErr = msg
                    if (isAuthFailure(msg)) {
                        disabledPrimaryKeys.add(key)
                    } else if (isRateLimit(msg) && candidatePrimary.size - offset <= 1 && !msg.contains("day", ignoreCase = true)) {
                        runCatching { Thread.sleep(1500) }
                        try {
                            return transcribeWithGroq(
                                audioFile = chunk.file,
                                apiKey = key,
                                translateToEnglish = translateToEnglish,
                                languageHint = languageHint,
                            )
                        } catch (retryErr: Exception) {
                            lastErr = retryErr.message ?: lastErr
                        }
                    }
                }
            }
        } else if (primaryKeys.isEmpty()) {
            lastErr = "Primary AI Subtitles API key not configured"
        }

        lastErrorRef.compareAndSet(null, lastErr)
        val candidateBackup = if (translateToEnglish) emptyList() else backupKeys.filter { it !in disabledBackupKeys }
        if (candidateBackup.isNotEmpty()) {
            val startIdx = chunkIndex % candidateBackup.size
            for (offset in candidateBackup.indices) {
                val bKey = candidateBackup[(startIdx + offset) % candidateBackup.size]
                if (bKey in disabledBackupKeys) continue
                try {
                    hadBackupFallback.set(true)
                    return transcribeWithDeepgram(chunk.file, bKey)
                } catch (e: Exception) {
                    val msg = e.message ?: e::class.java.simpleName
                    lastErr = msg
                    if (isAuthFailure(msg)) disabledBackupKeys.add(bKey)
                }
            }
        }

        throw RuntimeException(sanitizeErrorMessage(lastErr))
    }

    /**
     * Pipelined Streaming Extractor + Parallel Multi-Part Transcription:
     * Uploads Chunk 0 as soon as extracted while remaining chunks finish compressing/splitting,
     * transcribes chunks concurrently across active keys, and emits progressive live cues as early parts complete.
     */
    fun extractAndTranscribeCascade(
        context: Context,
        uri: Uri,
        cacheDir: File,
        preferredAudioOrdinal: Int = -1,
        preferEnglishFirst: Boolean = true,
        groqKey: String = DEFAULT_GROQ_KEY,
        deepgramKey: String = DEFAULT_DEEPGRAM_KEY,
        translateToEnglish: Boolean = false,
        languageHint: String? = null,
        fallbackChunkProvider: (() -> ExtractedChunk?)? = null,
        isCancelled: (() -> Boolean)? = null,
        onStageProgress: ((String, Int) -> Unit)? = null,
        onPartialCues: ((List<PlaybackRules.SubtitleCue>) -> Unit)? = null,
    ): TranscriptionResult {
        val primaryKeys = parseApiKeys(groqKey)
        val backupKeys = parseApiKeys(deepgramKey)
        if (primaryKeys.isEmpty() && backupKeys.isEmpty()) {
            throw RuntimeException("Configure an AI Subtitles API key in Settings or AI Subtitles")
        }

        val workerThreads = if (primaryKeys.size >= 2) 3 else 2
        val executor = Executors.newFixedThreadPool(workerThreads)
        val createdTempFiles = Collections.synchronizedList(mutableListOf<File>())
        val wordsByChunk = ConcurrentHashMap<Int, List<AutoSubEngine.WordInfo>>()
        val disabledPrimaryKeys = ConcurrentHashMap.newKeySet<String>()
        val disabledBackupKeys = ConcurrentHashMap.newKeySet<String>()
        val hadBackupFallback = AtomicBoolean(false)
        val firstPrimaryErr = AtomicReference<String?>(null)
        val workerFailure = AtomicReference<String?>(null)
        val extractionDone = AtomicBoolean(false)
        val totalChunks = AtomicInteger(0)
        val completedChunks = AtomicInteger(0)
        val emittedPrefix = AtomicInteger(-1)
        val partialLock = Any()
        val futures = Collections.synchronizedList(mutableListOf<java.util.concurrent.Future<*>>())

        fun tryEmitPartialPrefix() {
            if (onPartialCues == null) return
            synchronized(partialLock) {
                var next = emittedPrefix.get() + 1
                var advanced = false
                while (wordsByChunk.containsKey(next)) {
                    emittedPrefix.set(next)
                    next++
                    advanced = true
                }
                if (!advanced) return
                val upTo = emittedPrefix.get()
                if (extractionDone.get() && upTo >= totalChunks.get() - 1) return
                val partialWords = ArrayList<AutoSubEngine.WordInfo>()
                for (i in 0..upTo) {
                    partialWords.addAll(wordsByChunk[i].orEmpty())
                }
                if (partialWords.isNotEmpty()) {
                    onPartialCues.invoke(buildSubtitleCues(partialWords))
                }
            }
        }

        fun enqueueChunk(idx: Int, chunk: ExtractedChunk) {
            if (chunk.file.name.startsWith("temp_autosub_")) {
                createdTempFiles.add(chunk.file)
            }
            totalChunks.incrementAndGet()
            futures.add(executor.submit {
                if (isCancelled?.invoke() == true) return@submit
                try {
                    val chunkBaseSec = chunk.startMs / 1000.0
                    val rawWords = transcribeSingleChunkCascade(
                        chunk = chunk,
                        chunkIndex = idx,
                        primaryKeys = primaryKeys,
                        backupKeys = backupKeys,
                        disabledPrimaryKeys = disabledPrimaryKeys,
                        disabledBackupKeys = disabledBackupKeys,
                        hadBackupFallback = hadBackupFallback,
                        lastErrorRef = firstPrimaryErr,
                        translateToEnglish = translateToEnglish,
                        languageHint = languageHint,
                    )
                    val shifted = rawWords.map { w ->
                        AutoSubEngine.WordInfo(w.word, w.start + chunkBaseSec, w.end + chunkBaseSec)
                    }
                    wordsByChunk[idx] = shifted
                    val done = completedChunks.incrementAndGet()
                    if (extractionDone.get()) {
                        val total = totalChunks.get().coerceAtLeast(1)
                        val pct = 42 + ((done.toDouble() / total) * 48.0).toInt().coerceIn(0, 48)
                        val label = if (total > 1) "Generating AI Subtitles ($done/$total parts)..." else "Generating AI Subtitles..."
                        onStageProgress?.invoke(label, pct)
                    } else {
                        val pct = (38 + done * 10).coerceAtMost(80)
                        onStageProgress?.invoke("Generating AI Subtitles (part $done ready)...", pct)
                    }
                    tryEmitPartialPrefix()
                } catch (e: Exception) {
                    workerFailure.compareAndSet(null, e.message ?: e::class.java.simpleName)
                }
            })
        }

        try {
            onStageProgress?.invoke("Preparing audio...", 5)
            extractAudioChunksInternal(
                context = context,
                uri = uri,
                cacheDir = cacheDir,
                maxChunkBytes = MAX_GROQ_CHUNK_BYTES,
                preferredAudioOrdinal = preferredAudioOrdinal,
                preferEnglishFirst = preferEnglishFirst,
                onProgress = { p ->
                    if (completedChunks.get() == 0) {
                        val msg = if (p >= 20) "Extracting & splitting audio..." else "Preparing speech audio..."
                        onStageProgress?.invoke(msg, p.coerceIn(5, 40))
                    }
                },
                onChunkReady = { idx, chunk ->
                    enqueueChunk(idx, chunk)
                },
                isCancelled = isCancelled,
            )

            if (totalChunks.get() == 0 && isCancelled?.invoke() != true) {
                fallbackChunkProvider?.invoke()?.let { fallback ->
                    enqueueChunk(0, fallback)
                }
            }
            if (totalChunks.get() == 0) {
                throw RuntimeException("Could not extract audio track (file empty or unreadable)")
            }

            extractionDone.set(true)
            val total = totalChunks.get()
            val doneNow = completedChunks.get()
            if (doneNow < total) {
                val pct = 42 + ((doneNow.toDouble() / total) * 48.0).toInt().coerceIn(0, 48)
                val partLabel = if (total > 1) " ($doneNow/$total parts)" else ""
                onStageProgress?.invoke("Generating AI Subtitles$partLabel...", pct)
            }

            val pending = synchronized(futures) { futures.toList() }
            pending.forEach { it.get() }

            if (isCancelled?.invoke() == true) {
                throw RuntimeException("Cancelled")
            }

            workerFailure.get()?.let { err -> throw RuntimeException(err) }

            val merged = ArrayList<AutoSubEngine.WordInfo>()
            for (i in 0 until total) {
                merged.addAll(wordsByChunk[i].orEmpty())
            }
            if (merged.isEmpty()) {
                throw RuntimeException("No speech detected in audio")
            }
            return TranscriptionResult(
                words = merged,
                provider = "$total audio part${if (total > 1) "s" else ""}",
                groqError = if (hadBackupFallback.get()) firstPrimaryErr.get() else null,
            )
        } finally {
            executor.shutdownNow()
            runCatching { executor.awaitTermination(250, TimeUnit.MILLISECONDS) }
            val filesToClean = synchronized(createdTempFiles) { createdTempFiles.toList() }
            filesToClean.forEach { runCatching { it.delete() } }
        }
    }

    /**
     * Multi-Chunk, Multi-Key & Backup Parallel Transcription Pipeline:
     * Executes chunks concurrently across active keys with per-chunk failover and cached partial progress.
     */
    fun transcribeChunksCascade(
        chunks: List<ExtractedChunk>,
        groqKey: String = DEFAULT_GROQ_KEY,
        deepgramKey: String = DEFAULT_DEEPGRAM_KEY,
        onProgress: ((Int) -> Unit)? = null
    ): TranscriptionResult {
        if (chunks.isEmpty()) throw RuntimeException("No audio chunks available")

        val primaryKeys = parseApiKeys(groqKey)
        val backupKeys = parseApiKeys(deepgramKey)
        if (primaryKeys.isEmpty() && backupKeys.isEmpty()) {
            throw RuntimeException("Primary AI Subtitles API key not configured")
        }

        val workerThreads = minOf(chunks.size, if (primaryKeys.size >= 2) 3 else 2).coerceAtLeast(1)
        val executor = Executors.newFixedThreadPool(workerThreads)
        val wordsByChunk = ConcurrentHashMap<Int, List<AutoSubEngine.WordInfo>>()
        val disabledPrimaryKeys = ConcurrentHashMap.newKeySet<String>()
        val disabledBackupKeys = ConcurrentHashMap.newKeySet<String>()
        val hadBackupFallback = AtomicBoolean(false)
        val firstPrimaryErr = AtomicReference<String?>(null)
        val workerFailure = AtomicReference<String?>(null)
        val completed = AtomicInteger(0)

        try {
            val futures = chunks.mapIndexed { idx, chunk ->
                executor.submit {
                    try {
                        val chunkBaseSec = chunk.startMs / 1000.0
                        val rawWords = transcribeSingleChunkCascade(
                            chunk = chunk,
                            chunkIndex = idx,
                            primaryKeys = primaryKeys,
                            backupKeys = backupKeys,
                            disabledPrimaryKeys = disabledPrimaryKeys,
                            disabledBackupKeys = disabledBackupKeys,
                            hadBackupFallback = hadBackupFallback,
                            lastErrorRef = firstPrimaryErr,
                        )
                        wordsByChunk[idx] = rawWords.map { w ->
                            AutoSubEngine.WordInfo(w.word, w.start + chunkBaseSec, w.end + chunkBaseSec)
                        }
                        val done = completed.incrementAndGet()
                        onProgress?.invoke((45 + (done * 43) / chunks.size).coerceIn(45, 88))
                    } catch (e: Exception) {
                        workerFailure.compareAndSet(null, e.message ?: e::class.java.simpleName)
                    }
                }
            }
            futures.forEach { it.get() }
            workerFailure.get()?.let { throw RuntimeException(it) }

            val allWords = ArrayList<AutoSubEngine.WordInfo>()
            for (i in chunks.indices) {
                allWords.addAll(wordsByChunk[i].orEmpty())
            }
            if (allWords.isEmpty()) {
                throw RuntimeException("No speech detected in audio")
            }
            return TranscriptionResult(
                words = allWords,
                provider = "${chunks.size} audio part${if (chunks.size > 1) "s" else ""}",
                groqError = if (hadBackupFallback.get()) firstPrimaryErr.get() else null,
            )
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * Backward-compatible single-file cascade entry point.
     */
    fun transcribeCascade(
        audioFile: File,
        groqKey: String = DEFAULT_GROQ_KEY,
        deepgramKey: String = DEFAULT_DEEPGRAM_KEY,
        onProgress: ((Int) -> Unit)? = null
    ): List<AutoSubEngine.WordInfo> =
        transcribeChunksCascade(
            listOf(ExtractedChunk(audioFile, 0L, mimeForExtension(audioFile.extension))),
            groqKey,
            deepgramKey,
            onProgress
        ).words

    fun toSrt(cues: List<PlaybackRules.SubtitleCue>): String {
        val sb = StringBuilder()
        cues.forEachIndexed { i, cue ->
            sb.append(i + 1).append("\r\n")
            sb.append(formatSrtTime(cue.startMs)).append(" --> ").append(formatSrtTime(cue.endMs)).append("\r\n")
            sb.append(cue.text).append("\r\n\r\n")
        }
        return sb.toString()
    }

    fun formatSrtTime(timeMs: Long): String {
        val hours = timeMs / 3600000
        val minutes = (timeMs % 3600000) / 60000
        val seconds = (timeMs % 60000) / 1000
        val millis = timeMs % 1000
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    fun saveSrtFile(
        context: Context,
        videoTitle: String,
        cues: List<PlaybackRules.SubtitleCue>,
        videoFile: File? = null,
        langSuffix: String? = null,
    ): File? {
        val srtContent = toSrt(cues)
        val payloadBytes = if (!langSuffix.isNullOrBlank()) {
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + srtContent.toByteArray(Charsets.UTF_8)
        } else {
            srtContent.toByteArray(Charsets.UTF_8)
        }
        val cleanTitle = videoTitle.substringBeforeLast('.')
        val sanitized = cleanTitle.replace(Regex("""[^a-zA-Z0-9._ -]"""), "_").trim()
        val stem = if (sanitized.isNotBlank()) sanitized else "MSPlayer_Subtitles"
        val fileName = if (!langSuffix.isNullOrBlank()) "$stem.${langSuffix.trim().lowercase(Locale.ROOT)}.srt" else "$stem.srt"

        if (videoFile != null && videoFile.parentFile?.canWrite() == true) {
            try {
                val file = File(videoFile.parentFile, fileName)
                file.writeBytes(payloadBytes)
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("application/x-subrip"), null)
                return file
            } catch (_: Exception) {}
        }

        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloadDir != null && (downloadDir.exists() || downloadDir.mkdirs())) {
            try {
                val file = File(downloadDir, fileName)
                file.writeBytes(payloadBytes)
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("application/x-subrip"), null)
                return file
            } catch (_: Exception) {}
        }

        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        if (moviesDir != null) {
            val msDir = File(moviesDir, "MSPlayer")
            if (msDir.exists() || msDir.mkdirs()) {
                try {
                    val file = File(msDir, fileName)
                    file.writeBytes(payloadBytes)
                    MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("application/x-subrip"), null)
                    return file
                } catch (_: Exception) {}
            }
        }

        try {
            val extDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val file = File(extDir, fileName)
            file.writeBytes(payloadBytes)
            return file
        } catch (_: Exception) {}

        return null
    }

    private fun unescapeJson(raw: String): String =
        raw.replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", " ")
            .replace("\\/", "/")
            .trim()

    /**
     * Parses Whisper verbose_json & Deepgram Nova-2 JSON extracting word timestamps.
     * Prefers Deepgram's capitalized/punctuated `punctuated_word` when present.
     */
    fun parseVerboseJson(jsonString: String): List<AutoSubEngine.WordInfo> {
        val words = mutableListOf<AutoSubEngine.WordInfo>()

        val objectRegex = Regex("""\{([^{}]+)\}""")
        val punctWordRegex = Regex(""""punctuated_word"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        val wordFieldRegex = Regex(""""word"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        val startFieldRegex = Regex(""""start"\s*:\s*(-?[0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)""")
        val endFieldRegex = Regex(""""end"\s*:\s*(-?[0-9]+(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)""")
        val textFieldRegex = Regex(""""text"\s*:\s*"((?:[^"\\]|\\.)*)"""")

        for (match in objectRegex.findAll(jsonString)) {
            val block = match.groupValues[1]
            val punctMatch = punctWordRegex.find(block)
            val wordMatch = punctMatch ?: wordFieldRegex.find(block)
            val startMatch = startFieldRegex.find(block)
            val endMatch = endFieldRegex.find(block)

            if (wordMatch != null && startMatch != null && endMatch != null) {
                val w = unescapeJson(wordMatch.groupValues[1])
                val s = (startMatch.groupValues[1].toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
                val e = (endMatch.groupValues[1].toDoubleOrNull() ?: (s + 0.3)).coerceAtLeast(s)
                if (w.isNotEmpty()) {
                    words.add(AutoSubEngine.WordInfo(w, s, e))
                }
            }
        }

        if (words.isNotEmpty()) return words

        // Fallback: segments with "text", "start", "end"
        for (match in objectRegex.findAll(jsonString)) {
            val block = match.groupValues[1]
            val textMatch = textFieldRegex.find(block)
            val startMatch = startFieldRegex.find(block)
            val endMatch = endFieldRegex.find(block)

            if (textMatch != null && startMatch != null && endMatch != null) {
                val text = unescapeJson(textMatch.groupValues[1])
                val start = (startMatch.groupValues[1].toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
                val end = (endMatch.groupValues[1].toDoubleOrNull() ?: (start + 2.0)).coerceAtLeast(start + 0.1)
                val splitWords = text.split(Regex("""\s+""")).filter { it.isNotBlank() }
                if (splitWords.isNotEmpty()) {
                    val wordDuration = (end - start) / splitWords.size
                    for (wIdx in splitWords.indices) {
                        val wStart = start + wIdx * wordDuration
                        val wEnd = wStart + wordDuration
                        words.add(AutoSubEngine.WordInfo(splitWords[wIdx], wStart, wEnd))
                    }
                }
            }
        }
        return words
    }

    fun buildSubtitleCues(words: List<AutoSubEngine.WordInfo>): List<PlaybackRules.SubtitleCue> {
        val blocks = AutoSubEngine.createBalancedSrtBlocks(words)
        val timedBlocks = AutoSubEngine.applyTimingRules(blocks)
        return AutoSubEngine.toSubtitleCues(timedBlocks)
    }
}
