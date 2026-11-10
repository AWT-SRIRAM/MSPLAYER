package app.msplayer.offline

object PlaybackRules {
    fun displayDimensions(width: Int, height: Int, orientation: Int = 0, sarNum: Int = 1, sarDen: Int = 1): Pair<Int, Int> {
        val w = if (sarNum > 0 && sarDen > 0) (width.toLong() * sarNum / sarDen).toInt() else width
        return if (orientation in 4..7) height to w else w to height
    }
    fun videoOrientation(width: Int, height: Int): Int = when {
        width <= 0 || height <= 0 || width == height -> 0
        width > height -> 2
        else -> 1
    }

    fun videoScale(
        videoWidth: Float,
        videoHeight: Float,
        windowWidth: Float,
        windowHeight: Float,
        zoom: Float,
        fill: Boolean = false,
    ): Float {
        if (videoWidth <= 0 || videoHeight <= 0 || windowWidth <= 0 || windowHeight <= 0) return 0f
        val horizontal = windowWidth / videoWidth
        val vertical = windowHeight / videoHeight
        return (if (fill) maxOf(horizontal, vertical) else minOf(horizontal, vertical)) * zoom
    }

    private val audioExtensions =
        setOf(
            "mp3",
            "mp2",
            "m4a",
            "aac",
            "flac",
            "wav",
            "ogg",
            "opus",
            "ac3",
            "eac3",
            "ec3",
            "dts",
            "ape",
            "alac",
            "aiff",
            "aif",
            "wma",
            "amr",
            "mka",
            "caf",
            "dsf",
            "dff",
        )
    private val videoExtensions =
        setOf(
            "mkv",
            "mp4",
            "m4v",
            "mov",
            "avi",
            "webm",
            "wmv",
            "flv",
            "ts",
            "m2ts",
            "mts",
            "mpg",
            "mpeg",
            "vob",
            "ogv",
            "3gp",
            "3g2",
            "rm",
            "rmvb",
            "mxf",
            "asf",
        )

    fun temporaryFile(name: String): Boolean {
        val n = name.lowercase(java.util.Locale.ROOT)
        return n.startsWith(".pending") || n.endsWith(".pending") || n.endsWith(".part") || n.endsWith(".crdownload")
    }

    private fun mediaExtension(name: String): String {
        var n = name.lowercase(java.util.Locale.ROOT)
        for (suffix in listOf(".pending", ".part", ".crdownload")) if (n.endsWith(suffix)) n = n.removeSuffix(suffix)
        return n.substringAfterLast('.', "")
    }

    /** Small signature probe for temporary names with no usable MIME or suffix. */
    fun sniffMedia(bytes: ByteArray): String? {
        fun at(offset: Int, value: String) = bytes.size >= offset + value.length && value.indices.all { (bytes[offset + it].toInt() and 255) == value[it].code }
        return when {
            bytes.size >= 4 && bytes[0] == 0x1a.toByte() && bytes[1] == 0x45.toByte() && bytes[2] == 0xdf.toByte() && bytes[3] == 0xa3.toByte() -> "video/x-matroska"
            at(4, "ftyp") -> "video/mp4"
            at(0, "RIFF") && at(8, "AVI ") -> "video/x-msvideo"
            at(0, "RIFF") && at(8, "WAVE") -> "audio/wav"
            at(0, "fLaC") -> "audio/flac"
            at(0, "ID3") -> "audio/mpeg"
            else -> null
        }
    }

    fun audioFile(mime: String?, name: String): Boolean =
        when {
            mime?.startsWith("video/") == true -> false
            mime?.startsWith("audio/") == true -> true
            else -> mediaExtension(name) in audioExtensions
        }

    fun mediaFile(mime: String?, name: String) =
        mime?.startsWith("video/") == true ||
            audioFile(mime, name) ||
            mediaExtension(name) in videoExtensions

    fun fourcc(value: Int): String =
        (0..3).map { ((value ushr (it * 8)) and 255).toChar() }.joinToString("").trim()

    fun safeSeek(position: Long, duration: Long): Long =
        position.coerceIn(0, duration.coerceAtLeast(0))

    fun completed(position: Long, duration: Long) =
        duration > 0 && position >= duration - minOf(1500L, duration / 10) && position > 0

    fun resume(position: Long, duration: Long, watched: Boolean) =
        if (watched || completed(position, duration)) 0L else position.coerceAtLeast(0)

    fun subtitleBadge(codec: String): String =
        when (codec.trim().lowercase()) {
            "subt",
            "srt",
            "subrip" -> "SRT"
            "ssa",
            "ass" -> "ASS"
            "pgs",
            "pgss",
            "bdpg",
            "hdmv_pgs_subtitle" -> "PGS"
            "wvtt",
            "webvtt" -> "VTT"
            else -> codec.trim().uppercase().take(7)
        }

    data class SubtitleCue(
        val startMs: Long,
        val endMs: Long,
        val text: String,
    )

    fun parseSubtitleCues(raw: String): List<SubtitleCue> {
        val cues = ArrayList<SubtitleCue>()
        val lines = raw.lines()
        var i = 0
        val timeRegex = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})""")
        val ssaRegex = Regex("""^Dialogue:\s*\d+,(\d+):(\d{2}):(\d{2})\.(\d{2}),(\d+):(\d{2}):(\d{2})\.(\d{2}),[^,]*?,[^,]*?,[^,]*?,[^,]*?,[^,]*?,[^,]*?,(.*)$""")

        while (i < lines.size) {
            val line = lines[i].trim()
            val match = timeRegex.find(line)
            if (match != null) {
                val (h1, m1, s1, ms1, h2, m2, s2, ms2) = match.destructured
                val startMs = h1.toLong() * 3600000L + m1.toLong() * 60000L + s1.toLong() * 1000L + ms1.toLong()
                val endMs = h2.toLong() * 3600000L + m2.toLong() * 60000L + s2.toLong() * 1000L + ms2.toLong()
                i++
                val textBuilder = StringBuilder()
                while (i < lines.size && lines[i].trim().isNotEmpty()) {
                    val textLine = lines[i].trim()
                    if (textLine.matches(Regex("""^\d+$""")) && i + 1 < lines.size && timeRegex.containsMatchIn(lines[i + 1])) {
                        break
                    }
                    if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                    textBuilder.append(textLine)
                    i++
                }
                if (textBuilder.isNotEmpty()) {
                    cues += SubtitleCue(startMs, endMs, textBuilder.toString())
                }
                continue
            }

            val ssaMatch = ssaRegex.find(line)
            if (ssaMatch != null) {
                val (h1, m1, s1, cs1, h2, m2, s2, cs2, text) = ssaMatch.destructured
                val startMs = h1.toLong() * 3600000L + m1.toLong() * 60000L + s1.toLong() * 1000L + cs1.toLong() * 10L
                val endMs = h2.toLong() * 3600000L + m2.toLong() * 60000L + s2.toLong() * 1000L + cs2.toLong() * 10L
                var cleanText = text.replace(Regex("""\{[^}]*\\p[1-9][^}]*\}.*?(\{[^}]*\\p0[^}]*\}|$)""", RegexOption.DOT_MATCHES_ALL), "")
                    .replace(Regex("""\{[^}]*\}"""), "")
                    .replace("\\N", "\n").replace("\\n", "\n").trim()
                if (cleanText.matches(Regex("""^(?:[mnlbspc]\s+-?\d+(?:\s+-?\d+)*\s*)+$""", RegexOption.IGNORE_CASE))) {
                    cleanText = ""
                }
                if (cleanText.isNotEmpty()) {
                    cues += SubtitleCue(startMs, endMs, cleanText)
                }
            }
            i++
        }
        return cues.sortedBy { it.startMs }
    }

    fun parseCueTimestamps(text: String): List<Long> {
        return parseSubtitleCues(text).map { it.startMs }.sorted().distinct()
    }

    fun findNextCue(cues: List<Long>, currentMs: Long): Long? =
        cues.firstOrNull { it > currentMs + 500L }

    fun findPrevCue(cues: List<Long>, currentMs: Long): Long? =
        cues.lastOrNull { it < currentMs - 1200L }

    /**
     * O(log N + k) lookup for active subtitles at [effectivePos] on a list sorted by `startMs`.
     * Scans a short window backward to capture multi-second overlapping cues without full linear scans.
     */
    fun findActiveCueLines(cues: List<SubtitleCue>, effectivePos: Long): List<String> {
        if (cues.isEmpty()) return emptyList()
        val bs = cues.binarySearchBy(effectivePos) { it.startMs }
        val insertIdx = if (bs >= 0) bs else -(bs + 1)
        var startIdx = (insertIdx - 1).coerceAtLeast(0)
        var backSteps = 0
        while (startIdx > 0 && backSteps < 16 && (effectivePos - cues[startIdx].startMs) <= 20_000L) {
            startIdx--
            backSteps++
        }
        val active = ArrayList<String>(2)
        for (i in startIdx until cues.size) {
            val cue = cues[i]
            if (cue.startMs > effectivePos) break
            if (effectivePos <= cue.endMs) {
                val text = cue.text.trim()
                if (text.isNotBlank() && text !in active) {
                    active.add(text)
                }
            }
        }
        return active
    }

    fun isStreamUrl(url: String): Boolean {
        val lower = url.trim().lowercase(java.util.Locale.ROOT)
        return lower.startsWith("http://") || lower.startsWith("https://") ||
                lower.startsWith("rtsp://") || lower.startsWith("rtmp://") ||
                lower.startsWith("mms://") || lower.startsWith("udp://")
    }

    /**
     * Extracts or normalizes a stream URL pasted from clipboard or shared from another app.
     * Supports raw URLs, shared message text containing an HTTP/RTSP/RTMP URL, and bare domains.
     */
    fun normalizeStreamUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val urlRegex = Regex("""(?i)\b(?:https?|rtsp|rtmp|mms|udp)://[^\s<>"']+""")
        urlRegex.find(trimmed)?.value?.let { return it.trimEnd('.', ',', ';', ')', ']', '!') }
        val lower = trimmed.lowercase(java.util.Locale.ROOT)
        if (!trimmed.contains(' ') && (trimmed.contains('.') || lower.startsWith("localhost"))) {
            if (isStreamUrl(trimmed)) return trimmed
            return "https://$trimmed"
        }
        return trimmed
    }

    /**
     * Returns true when a URL looks like a social/video webpage (HTML) rather than a direct
     * media file or manifest stream (.mp4, .mkv, .m3u8, .mpd, etc.).
     */
    fun isWebpageWrapperUrl(url: String): Boolean {
        val clean = normalizeStreamUrl(url).lowercase(java.util.Locale.ROOT)
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) return false
        val path = clean.substringBefore('?').substringBefore('#')
        val directExt = listOf(".mp4", ".mkv", ".webm", ".mov", ".avi", ".ts", ".m3u8", ".mpd", ".mp3", ".m4a", ".aac", ".flac", ".ogg", ".opus")
        if (directExt.any { path.endsWith(it) }) return false
        val host = clean.substringAfter("://").substringBefore('/').substringBefore(':').removePrefix("www.").removePrefix("m.")
        return host in setOf(
            "youtube.com", "youtu.be", "music.youtube.com",
            "instagram.com", "tiktok.com", "facebook.com", "fb.watch",
            "vimeo.com", "dailymotion.com", "x.com", "twitter.com"
        )
    }

    enum class AspectOption(val label: String) {
        BEST_FIT("Best fit"),
        CROP("Crop"),
        STRETCH("Stretch"),
        RATIO_16_9("16:9"),
        RATIO_4_3("4:3"),
        ORIGINAL("Original");

        fun next(): AspectOption = entries[(ordinal + 1) % entries.size]
    }

    /**
     * Picks the primary subtitle track from available (id, label) pairs.
     * Prefers an exact [preferredName] match when present; otherwise selects the best
     * full-dialogue track (prioritizing English full dialogue over forced/signs-only tracks
     * while preserving original track order on ties).
     */
    fun choosePrimarySubtitle(
        tracks: List<Pair<Int, String>>,
        preferredName: String? = null,
    ): Pair<Int, String>? {
        val valid = tracks.filter { it.first >= 0 }
        if (valid.isEmpty()) return null

        val pref = preferredName?.trim()?.takeIf { it.isNotEmpty() }
        if (pref != null) {
            valid.firstOrNull { it.second.equals(pref, ignoreCase = true) }?.let { return it }
        }

        return valid.withIndex()
            .maxWithOrNull(
                compareBy<IndexedValue<Pair<Int, String>>> { (_, entry) ->
                    val lower = entry.second.lowercase(java.util.Locale.ROOT)
                    val isForcedOrSigns =
                        lower.contains("forced") ||
                            lower.contains("signs") ||
                            lower.contains("songs") ||
                            lower.contains("karaoke")
                    val isEnglish =
                        lower.contains("english") ||
                            Regex("""(?:^|[^a-z])(en|eng)(?:[^a-z]|$)""").containsMatchIn(lower)
                    var score = 0
                    if (isForcedOrSigns) score -= 100
                    if (isEnglish) score += 20
                    score
                }.thenByDescending { it.index }
            )
            ?.value
    }
}
