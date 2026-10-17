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
}
