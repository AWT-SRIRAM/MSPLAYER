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
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.Locale

/**
 * AI Subtitles Speech-to-Text Client & Compression Pipeline.
 * Compresses speech tracks into compact Mono AAC chunks, prioritizes English audio tracks,
 * uploads over RFC-2046 streaming multipart with backup failover and multi-key rotation.
 */
object SpeechToTextClient {

    const val PROVIDER_GROQ = "groq"
    const val PROVIDER_DEEPGRAM = "deepgram"

    val DEFAULT_GROQ_KEY: String get() = BuildConfig.DEFAULT_GROQ_KEY
    val DEFAULT_DEEPGRAM_KEY: String get() = BuildConfig.DEFAULT_DEEPGRAM_KEY
    val DEFAULT_CLOUDFLARE_TOKEN: String get() = BuildConfig.DEFAULT_CLOUDFLARE_TOKEN

    const val GROQ_ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
    const val DEEPGRAM_ENDPOINT = "https://api.deepgram.com/v1/listen?model=nova-2&smart_format=true"

    // Keep chunks strictly under upload caps (12 MB / 15 min gives huge headroom over 25 MB limit)
    const val MAX_GROQ_CHUNK_BYTES = 12L * 1024L * 1024L
    const val MAX_CHUNK_DURATION_US = 15L * 60L * 1_000_000L
    const val TARGET_SPEECH_BITRATE = 28_000

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

    private fun estimateSourceSizeBytes(context: Context, uri: Uri): Long {
        if (uri.scheme == "file") {
            uri.path?.let { return File(it).length() }
        }
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        }.getOrDefault(-1L)
    }

    /**
     * Extracts audio track (preferring English audio first on dual-audio containers) into
     * compact 28 kbps Mono AAC-LC chunks (<= 12 MB / <= 15 min) so large 34 MB–1 GB tracks
     * stream cleanly within memory and API size limits.
     */
    fun extractAudioChunks(
        context: Context,
        uri: Uri,
        cacheDir: File,
        maxChunkBytes: Long = MAX_GROQ_CHUNK_BYTES,
        preferredAudioOrdinal: Int = -1,
        preferEnglishFirst: Boolean = true,
        onProgress: ((Int) -> Unit)? = null
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
            if (candidates.isEmpty()) return emptyList()

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
            val inChannels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else 2
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

            val isSpeechCompactCodec =
                audioMime.contains("aac", true) || audioMime.contains("mp4a", true) || audioMime.contains("opus", true)
            val needsCompression = !directSupported ||
                !isSpeechCompactCodec ||
                inChannels > 1 ||
                estimatedAudioBytes < 0L ||
                estimatedAudioBytes > 5L * 1024L * 1024L

            if (needsCompression) {
                val compressed = transcodeAudioToAacChunks(
                    extractor = extractor,
                    inputFormat = audioFormat,
                    totalDurationUs = totalDurationUs,
                    cacheDir = cacheDir,
                    maxChunkBytes = maxChunkBytes,
                    onProgress = onProgress,
                )
                if (compressed.isNotEmpty() || !directSupported) {
                    return compressed
                }
                // Rewind only if transcoder failed on a direct-muxable track
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

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
                    chunks.add(ExtractedChunk(f, chunkStartUs / 1000L, contentMime))
                } else {
                    f?.delete()
                }
                currentFile = null
                chunkStartUs = -1L
                chunkBytes = 0L
            }

            try {
                while (true) {
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

                    if (chunkBytes >= maxChunkBytes || (sampleUs - chunkStartUs) >= MAX_CHUNK_DURATION_US) {
                        closeCurrentChunk()
                    }
                }
                closeCurrentChunk()
            } finally {
                try { muxer?.release() } catch (_: Exception) {}
            }

            onProgress?.invoke(40)
            return chunks
        } catch (_: Exception) {
            return emptyList()
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /**
     * Streaming hardware speech compressor: decodes any multi-channel/lossless/high-bitrate audio
     * (stereo AAC, 5.1 AC-3/EAC-3/DTS, FLAC, MP3, up to multi-GB sources) with zero-drop PCM queuing
     * and clean sample-rate decimation into compact 28 kbps Mono AAC-LC `.m4a` chunks (~210 KB/min).
     */
    private fun transcodeAudioToAacChunks(
        extractor: MediaExtractor,
        inputFormat: MediaFormat,
        totalDurationUs: Long,
        cacheDir: File,
        maxChunkBytes: Long,
        onProgress: ((Int) -> Unit)?
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
            private var buf = ShortArray(32768)
            private var head = 0
            private var tail = 0
            val available: Int get() = tail - head

            fun push(value: Short) {
                if (tail == buf.size) {
                    if (head > 0) {
                        val len = tail - head
                        System.arraycopy(buf, head, buf, 0, len)
                        head = 0
                        tail = len
                    } else {
                        buf = buf.copyOf(buf.size * 2)
                    }
                }
                buf[tail++] = value
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
                decimationFactor = when (rateHz) {
                    48000 -> 3
                    44100 -> 2
                    32000 -> 2
                    else -> 1
                }
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
                        setInteger(MediaFormat.KEY_BIT_RATE, TARGET_SPEECH_BITRATE)
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
                    chunks.add(ExtractedChunk(f, chunkStartUs / 1000L, "audio/m4a"))
                } else {
                    f?.delete()
                }
                currentFile = null
                chunkStartUs = -1L
                chunkBytes = 0L
            }

            var pcmScratch = ShortArray(8192)
            while (!encoderDone) {
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
                                        pcmFifo.push(mono.coerceIn(-32768, 32767).toShort())
                                    } else {
                                        decimAccum += mono
                                        decimPhase++
                                        if (decimPhase >= decimationFactor) {
                                            val avg = (decimAccum / decimationFactor).toInt().coerceIn(-32768, 32767).toShort()
                                            pcmFifo.push(avg)
                                            decimAccum = 0L
                                            decimPhase = 0
                                        }
                                    }
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
                            if (muxer != null && (chunkBytes >= maxChunkBytes || (samplePts - chunkStartUs) >= MAX_CHUNK_DURATION_US)) {
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
     * Sends a compressed audio chunk (<= 25 MB) to primary speech recognition endpoint.
     */
    fun transcribeWithGroq(
        audioFile: File,
        apiKey: String,
        onProgress: ((Int) -> Unit)? = null
    ): List<AutoSubEngine.WordInfo> {
        val boundary = generateMultipartBoundary()
        val lineEnd = "\r\n"
        val twoHyphens = "--"

        val url = URL(GROQ_ENDPOINT)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doInput = true
        conn.doOutput = true
        conn.useCaches = false
        conn.setChunkedStreamingMode(64 * 1024)
        conn.connectTimeout = 60000
        conn.readTimeout = 180000
        conn.setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 MSPLAYER/0.6.1")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

        val mime = mimeForExtension(audioFile.extension)
        val totalBytes = audioFile.length().coerceAtLeast(1L)
        var uploadedBytes = 0L

        DataOutputStream(conn.outputStream).use { dos ->
            dos.writeBytes("$twoHyphens$boundary$lineEnd")
            dos.writeBytes("Content-Disposition: form-data; name=\"model\"$lineEnd$lineEnd")
            dos.writeBytes("whisper-large-v3-turbo$lineEnd")

            dos.writeBytes("$twoHyphens$boundary$lineEnd")
            dos.writeBytes("Content-Disposition: form-data; name=\"response_format\"$lineEnd$lineEnd")
            dos.writeBytes("verbose_json$lineEnd")

            dos.writeBytes("$twoHyphens$boundary$lineEnd")
            dos.writeBytes("Content-Disposition: form-data; name=\"timestamp_granularities[]\"$lineEnd$lineEnd")
            dos.writeBytes("word$lineEnd")

            dos.writeBytes("$twoHyphens$boundary$lineEnd")
            dos.writeBytes("Content-Disposition: form-data; name=\"temperature\"$lineEnd$lineEnd")
            dos.writeBytes("0$lineEnd")

            dos.writeBytes("$twoHyphens$boundary$lineEnd")
            dos.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"${audioFile.name}\"$lineEnd")
            dos.writeBytes("Content-Type: $mime$lineEnd$lineEnd")

            FileInputStream(audioFile).use { fis ->
                val buffer = ByteArray(16384)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    dos.write(buffer, 0, bytesRead)
                    uploadedBytes += bytesRead
                    val p = 45 + ((uploadedBytes.toDouble() / totalBytes) * 35.0).toInt().coerceIn(0, 35)
                    onProgress?.invoke(p)
                }
            }
            dos.writeBytes(lineEnd)
            dos.writeBytes("$twoHyphens$boundary$twoHyphens$lineEnd")
            dos.flush()
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
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doInput = true
        conn.doOutput = true
        conn.useCaches = false
        conn.setChunkedStreamingMode(64 * 1024)
        conn.connectTimeout = 60000
        conn.readTimeout = 180000
        conn.setRequestProperty("Authorization", "Token ${apiKey.trim()}")
        conn.setRequestProperty("Content-Type", mimeForExtension(audioFile.extension))
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 MSPLAYER/0.6.1")

        val totalBytes = audioFile.length().coerceAtLeast(1L)
        var uploadedBytes = 0L

        DataOutputStream(conn.outputStream).use { dos ->
            FileInputStream(audioFile).use { fis ->
                val buffer = ByteArray(16384)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } != -1) {
                    dos.write(buffer, 0, bytesRead)
                    uploadedBytes += bytesRead
                    val p = 45 + ((uploadedBytes.toDouble() / totalBytes) * 35.0).toInt().coerceIn(0, 35)
                    onProgress?.invoke(p)
                }
            }
            dos.flush()
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

    /**
     * Multi-Chunk, Multi-Key & Backup Transcription Pipeline:
     * Rotates across any comma-separated primary keys before falling back cleanly
     * to backup keys if exhausted, surfacing provider-neutral diagnostics.
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
        var primaryErr: String? = null

        for (candidateKey in primaryKeys) {
            try {
                val allWords = mutableListOf<AutoSubEngine.WordInfo>()
                chunks.forEachIndexed { idx, chunk ->
                    val chunkBaseSec = chunk.startMs / 1000.0
                    val spanStart = 45 + (idx * 43 / chunks.size)
                    val spanSize = (43 / chunks.size).coerceAtLeast(5)
                    val rawWords = transcribeWithGroq(chunk.file, candidateKey) { p ->
                        val rel = ((p - 45).coerceIn(0, 43) / 43.0)
                        onProgress?.invoke((spanStart + rel * spanSize).toInt().coerceIn(45, 88))
                    }
                    rawWords.forEach { w ->
                        allWords.add(AutoSubEngine.WordInfo(w.word, w.start + chunkBaseSec, w.end + chunkBaseSec))
                    }
                }
                if (allWords.isNotEmpty()) {
                    return TranscriptionResult(
                        words = allWords,
                        provider = "${chunks.size} audio part${if (chunks.size > 1) "s" else ""}"
                    )
                }
                primaryErr = "No speech detected in audio"
            } catch (e: Exception) {
                primaryErr = e.message ?: e::class.java.simpleName
            }
        }
        if (primaryKeys.isEmpty()) {
            primaryErr = "Primary AI Subtitles API key not configured"
        }

        for (backupKey in backupKeys) {
            try {
                val allWords = mutableListOf<AutoSubEngine.WordInfo>()
                chunks.forEachIndexed { idx, chunk ->
                    val chunkBaseSec = chunk.startMs / 1000.0
                    val spanStart = 45 + (idx * 43 / chunks.size)
                    val spanSize = (43 / chunks.size).coerceAtLeast(5)
                    val rawWords = transcribeWithDeepgram(chunk.file, backupKey) { p ->
                        val rel = ((p - 45).coerceIn(0, 43) / 43.0)
                        onProgress?.invoke((spanStart + rel * spanSize).toInt().coerceIn(45, 88))
                    }
                    rawWords.forEach { w ->
                        allWords.add(AutoSubEngine.WordInfo(w.word, w.start + chunkBaseSec, w.end + chunkBaseSec))
                    }
                }
                if (allWords.isNotEmpty()) {
                    return TranscriptionResult(
                        words = allWords,
                        provider = "${chunks.size} audio part${if (chunks.size > 1) "s" else ""}",
                        groqError = primaryErr
                    )
                }
            } catch (e: Exception) {
                primaryErr = e.message ?: primaryErr
            }
        }

        throw RuntimeException(primaryErr ?: "AI Subtitles service unavailable or keys unconfigured")
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
        videoFile: File? = null
    ): File? {
        val srtContent = toSrt(cues)
        val cleanTitle = videoTitle.substringBeforeLast('.')
        val sanitized = cleanTitle.replace(Regex("""[^a-zA-Z0-9._ -]"""), "_").trim()
        val baseName = if (sanitized.isNotBlank()) sanitized else "MSPlayer_Subtitles"

        if (videoFile != null && videoFile.parentFile?.canWrite() == true) {
            try {
                val file = File(videoFile.parentFile, "$baseName.srt")
                file.writeText(srtContent)
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("application/x-subrip"), null)
                return file
            } catch (_: Exception) {}
        }

        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloadDir != null && (downloadDir.exists() || downloadDir.mkdirs())) {
            try {
                val file = File(downloadDir, "$baseName.srt")
                file.writeText(srtContent)
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("application/x-subrip"), null)
                return file
            } catch (_: Exception) {}
        }

        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        if (moviesDir != null) {
            val msDir = File(moviesDir, "MSPlayer")
            if (msDir.exists() || msDir.mkdirs()) {
                try {
                    val file = File(msDir, "$baseName.srt")
                    file.writeText(srtContent)
                    MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("application/x-subrip"), null)
                    return file
                } catch (_: Exception) {}
            }
        }

        try {
            val extDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val file = File(extDir, "$baseName.srt")
            file.writeText(srtContent)
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
        val startFieldRegex = Regex(""""start"\s*:\s*([0-9.]+)""")
        val endFieldRegex = Regex(""""end"\s*:\s*([0-9.]+)""")
        val textFieldRegex = Regex(""""text"\s*:\s*"((?:[^"\\]|\\.)*)"""")

        for (match in objectRegex.findAll(jsonString)) {
            val block = match.groupValues[1]
            val punctMatch = punctWordRegex.find(block)
            val wordMatch = punctMatch ?: wordFieldRegex.find(block)
            val startMatch = startFieldRegex.find(block)
            val endMatch = endFieldRegex.find(block)

            if (wordMatch != null && startMatch != null && endMatch != null) {
                val w = unescapeJson(wordMatch.groupValues[1])
                val s = startMatch.groupValues[1].toDoubleOrNull() ?: 0.0
                val e = endMatch.groupValues[1].toDoubleOrNull() ?: (s + 0.3)
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
                val start = startMatch.groupValues[1].toDoubleOrNull() ?: 0.0
                val end = endMatch.groupValues[1].toDoubleOrNull() ?: (start + 2.0)
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
