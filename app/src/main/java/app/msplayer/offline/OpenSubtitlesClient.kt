package app.msplayer.offline

import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/**
 * OpenSubtitles.org / OpenSubtitles REST API v1 client, 64-bit moviehash calculator,
 * browser fallback builder, and .srt / .ass / .vtt / .zip archive extractor.
 */
object OpenSubtitlesClient {

    const val API_BASE = "https://api.opensubtitles.com/api/v1"
    const val WEB_BASE = "https://www.opensubtitles.org"
    const val USER_AGENT = "MSPLAYER v0.6.2"
    private const val MAX_ARCHIVE_BYTES = 16 * 1024 * 1024

    val DEFAULT_API_KEY: String get() = BuildConfig.DEFAULT_OPENSUBTITLES_KEY

    data class LanguageFilter(
        val code2: String,
        val code3: String,
        val label: String,
    )

    val languageOptions = listOf(
        LanguageFilter("en", "eng", "English"),
        LanguageFilter("", "all", "All"),
        LanguageFilter("hi", "hin", "Hindi"),
        LanguageFilter("ta", "tam", "Tamil"),
        LanguageFilter("ja", "jpn", "Japanese"),
        LanguageFilter("es", "spa", "Spanish"),
    )

    val subtitleExtensions = setOf("srt", "ass", "ssa", "vtt", "sub", "idx", "sup")

    data class SubtitleCandidate(
        val fileId: Int,
        val fileName: String,
        val release: String,
        val language: String,
        val downloadCount: Int,
        val fps: Double,
        val hashMatch: Boolean,
        val uploader: String,
        val hearingImpaired: Boolean,
    )

    data class DownloadResult(
        val file: File,
        val allExtracted: List<File>,
        val remainingDownloads: Int?,
    )

    fun activeApiKey(context: Context): String =
        Store.prefs(context).getString("opensubtitles_api_key", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_API_KEY

    fun saveApiKey(context: Context, key: String) {
        val clean = key.trim()
        Store.prefs(context).edit().apply {
            if (clean.isEmpty()) remove("opensubtitles_api_key") else putString("opensubtitles_api_key", clean)
        }.apply()
    }

    fun activeToken(context: Context): String =
        Store.prefs(context).getString("opensubtitles_token", "")?.trim().orEmpty()

    fun activeUsername(context: Context): String =
        Store.prefs(context).getString("opensubtitles_username", "")?.trim().orEmpty()

    fun saveAuth(context: Context, username: String, token: String) {
        Store.prefs(context).edit()
            .putString("opensubtitles_username", username.trim())
            .putString("opensubtitles_token", token.trim())
            .apply()
    }

    fun clearAuth(context: Context) {
        Store.prefs(context).edit()
            .remove("opensubtitles_username")
            .remove("opensubtitles_token")
            .apply()
    }

    fun cleanMediaQuery(title: String): String {
        var text = title.trim()
        if (text.contains("://")) {
            val seg = text.substringBefore('?').substringBefore('#').substringAfterLast('/')
            text = runCatching { URLDecoder.decode(seg, "UTF-8") }.getOrDefault(seg)
        }
        text = text.replace(Regex("""\.(?:mkv|mp4|m4v|avi|webm|mov|ts|mts|m2ts|flv|wmv|srt|ass|ssa|vtt|zip)$""", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("""@\S+"""), " ")
        text = text.replace(Regex("""\(\s*((?:19|20)\d{2})\s*\)"""), " $1 ")
        text = text.replace(Regex("""\[[^\]]*\]"""), " ")
        text = text.replace(Regex("""\([^)]*\)"""), " ")
        text = text.replace(Regex("""[._]+"""), " ")
        text = text.replace(
            Regex(
                """\b(?:480p|720p|1080p|1440p|2160p|4k|uhd|sdr|hdr|hdr10|dv|dolby|10bit|8bit|dual|multi|audio|sub|subs|subbed|dubbed|x264|x265|h264|h265|hevc|av1|aac|ac3|eac3|ddp\d*|dts|flac|bluray|blu-ray|bdrip|brrip|webrip|web-dl|webdl|web|hdtv|dvdrip|hdrip|remux|proper|repack|uncut|extended)\b""",
                RegexOption.IGNORE_CASE,
            ),
            " ",
        )
        text = text.replace(Regex("""(?:\s+-\s+|^\s*-\s*|\s*-\s*$)"""), " ")
        return text.replace(Regex("""\s+"""), " ").trim()
    }

    fun browserSearchUrl(query: String, langCode3: String = "eng"): String {
        val lang = langCode3.trim().lowercase(Locale.ROOT).ifBlank { "all" }
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        return if (encodedQuery.isBlank()) {
            "$WEB_BASE/en/search/sublanguageid-$lang"
        } else {
            "$WEB_BASE/en/search2/sublanguageid-$lang/moviename-$encodedQuery"
        }
    }

    fun computeMovieHash(file: File): String? {
        if (!file.exists() || !file.isFile) return null
        return runCatching {
            FileInputStream(file).channel.use { channel ->
                computeMovieHash(channel, file.length())
            }
        }.getOrNull()
    }

    fun computeMovieHash(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") {
            return uri.path?.let { computeMovieHash(File(it)) }
        }
        if (PlaybackRules.isStreamUrl(uri.toString())) return null
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val size = pfd.statSize
                if (size < 131072L) return null
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    computeMovieHash(ch, size)
                }
            }
        }.getOrNull()
    }

    fun computeMovieHash(channel: FileChannel, fileSize: Long): String? {
        if (fileSize < 131072L) return null
        val chunkSize = 65536
        var hash = fileSize.toULong()
        val buf = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)

        channel.position(0L)
        buf.clear()
        while (buf.hasRemaining()) {
            if (channel.read(buf) < 0) break
        }
        buf.flip()
        while (buf.remaining() >= 8) {
            hash += buf.getLong().toULong()
        }

        channel.position((fileSize - chunkSize).coerceAtLeast(0L))
        buf.clear()
        while (buf.hasRemaining()) {
            if (channel.read(buf) < 0) break
        }
        buf.flip()
        while (buf.remaining() >= 8) {
            hash += buf.getLong().toULong()
        }
        return hash.toString(16).padStart(16, '0')
    }

    fun searchSubtitles(
        apiKey: String,
        query: String,
        languageCode2: String = "en",
        movieHash: String? = null,
        tmdbId: Int? = null,
        imdbId: String? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ): List<SubtitleCandidate> {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) error("OpenSubtitles API key required for in-app search")
        val cleanQuery = query.trim()
        val cleanImdb = imdbId?.trim()?.removePrefix("tt")?.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
        if (cleanQuery.isBlank() && movieHash.isNullOrBlank() && tmdbId == null && cleanImdb == null) {
            error("Enter a title to search")
        }

        val params = buildList {
            if (!movieHash.isNullOrBlank()) add("moviehash=${URLEncoder.encode(movieHash.trim(), "UTF-8")}")
            if (tmdbId != null && tmdbId > 0) add("tmdb_id=$tmdbId")
            if (cleanImdb != null) add("imdb_id=$cleanImdb")
            if (cleanQuery.isNotBlank()) add("query=${URLEncoder.encode(cleanQuery, "UTF-8")}")
            if (seasonNumber != null && seasonNumber > 0) add("season_number=$seasonNumber")
            if (episodeNumber != null && episodeNumber > 0) add("episode_number=$episodeNumber")
            if (languageCode2.isNotBlank()) add("languages=${URLEncoder.encode(languageCode2.trim().lowercase(Locale.ROOT), "UTF-8")}")
        }.joinToString("&")

        val conn = (URL("$API_BASE/subtitles?$params").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20000
            readTimeout = 25000
            setRequestProperty("Api-Key", cleanKey)
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        val code = conn.responseCode
        val body = if (code in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            when (code) {
                401, 403 -> error("Invalid or unauthorized OpenSubtitles API key ($code)")
                429 -> error("OpenSubtitles rate limit reached. Try again in a few seconds.")
                else -> error("OpenSubtitles search error HTTP $code${err.take(120).let { if (it.isNotBlank()) ": $it" else "" }}")
            }
        }
        return parseSearchResponse(body)
    }

    fun login(apiKey: String, username: String, password: String): String {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) error("Enter OpenSubtitles API key first")
        val u = escapeJson(username.trim())
        val p = escapeJson(password)
        val payload = """{"username":"$u","password":"$p"}"""

        val conn = (URL("$API_BASE/login").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doInput = true
            doOutput = true
            connectTimeout = 20000
            readTimeout = 25000
            setRequestProperty("Api-Key", cleanKey)
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val body = if (code in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            error("Login failed ($code): ${err.take(120)}")
        }
        val root = JsonLite.parseObject(body)
        val token = root["token"]?.toString()?.trim().orEmpty()
        if (token.isBlank()) error("No auth token returned by OpenSubtitles")
        return token
    }

    fun downloadSubtitle(
        context: Context,
        apiKey: String,
        candidate: SubtitleCandidate,
        videoTitle: String,
        videoFile: File? = null,
        bearerToken: String = "",
    ): DownloadResult {
        val cleanKey = apiKey.trim()
        if (cleanKey.isBlank()) error("OpenSubtitles API key required")

        val payload = """{"file_id":${candidate.fileId}}"""
        val conn = (URL("$API_BASE/download").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doInput = true
            doOutput = true
            connectTimeout = 20000
            readTimeout = 30000
            setRequestProperty("Api-Key", cleanKey)
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            if (bearerToken.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${bearerToken.trim()}")
            }
        }
        conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val body = if (code in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            when (code) {
                406 -> error("Daily OpenSubtitles download limit reached for free/guest quota. Use \"Open in Browser\" or sign in.")
                401, 403 -> error("Unauthorized download ($code). Check OpenSubtitles API key or account.")
                else -> error("Download request failed ($code): ${err.take(120)}")
            }
        }

        val root = JsonLite.parseObject(body)
        val link = root["link"]?.toString()?.trim().orEmpty()
        if (link.isBlank()) error("OpenSubtitles did not return a download link")
        val remaining = (root["remaining"] as? Number)?.toInt()
        val remoteName = root["file_name"]?.toString()?.takeIf { it.isNotBlank() } ?: candidate.fileName

        val fileConn = (URL(link).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 20000
            readTimeout = 30000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        val fileCode = fileConn.responseCode
        if (fileCode !in 200..299) {
            error("Subtitle file fetch failed (HTTP $fileCode)")
        }

        val destDir = chooseDestinationDir(context, videoFile)
        val extracted = fileConn.inputStream.use { input ->
            extractSubtitleFromStream(
                input = input,
                originalName = remoteName,
                destDir = destDir,
                preferredPrefix = sanitizeStem(videoTitle.substringBeforeLast('.')),
            )
        }
        if (extracted.isEmpty()) error("Downloaded file contained no supported subtitle (.srt/.ass/.vtt)")
        return DownloadResult(extracted.first(), extracted, remaining)
    }

    fun chooseDestinationDir(context: Context, videoFile: File?): File {
        if (videoFile != null && videoFile.parentFile?.canWrite() == true) {
            return videoFile.parentFile!!
        }
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (downloadDir != null && (downloadDir.exists() || downloadDir.mkdirs()) && downloadDir.canWrite()) {
            return downloadDir
        }
        val appSubs = File(context.filesDir, "imported_subtitles")
        if (!appSubs.exists()) appSubs.mkdirs()
        return appSubs
    }

    /**
     * Reads a subtitle stream (.srt, .ass, .ssa, .vtt, .sub, .idx, .sup, .gz, or .zip archive),
     * safely extracting supported subtitle files into [destDir].
     */
    fun extractSubtitleFromStream(
        input: InputStream,
        originalName: String,
        destDir: File,
        preferredPrefix: String? = null,
    ): List<File> {
        if (!destDir.exists()) destDir.mkdirs()
        val bytes = readBoundedBytes(input, MAX_ARCHIVE_BYTES)
        if (bytes.isEmpty()) return emptyList()

        val hasZipMagic = bytes.size >= 4 &&
            bytes[0] == 0x50.toByte() &&
            bytes[1] == 0x4B.toByte() &&
            (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())

        if (hasZipMagic) {
            val outFiles = mutableListOf<File>()
            val prefix = sanitizeStem(preferredPrefix ?: "").ifBlank { "OpenSubtitles" }
            runCatching {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val entryBase = entry.name.substringAfterLast('/').substringAfterLast('\\')
                        val ext = entryBase.substringAfterLast('.', "").lowercase(Locale.ROOT)
                        if (!entry.isDirectory &&
                            !entry.name.contains("__MACOSX", ignoreCase = true) &&
                            entryBase.isNotBlank() &&
                            ext in subtitleExtensions
                        ) {
                            val content = readBoundedBytes(zis, MAX_ARCHIVE_BYTES)
                            if (content.isNotEmpty()) {
                                val cleanEntryName = sanitizeStem(entryBase.substringBeforeLast('.')).ifBlank { prefix }
                                val fileName = if (cleanEntryName.startsWith(prefix, ignoreCase = true)) {
                                    "$cleanEntryName.$ext"
                                } else {
                                    "${prefix}.${cleanEntryName}.$ext"
                                }
                                val target = uniqueFile(destDir, fileName)
                                target.writeBytes(content)
                                outFiles += target
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }
            if (outFiles.isNotEmpty()) {
                return outFiles.sortedBy {
                    when (it.extension.lowercase(Locale.ROOT)) {
                        "srt" -> 0
                        "ass", "ssa" -> 1
                        "vtt" -> 2
                        else -> 3
                    }
                }
            }
        }

        val isGzip = bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()
        val finalBytes = if (isGzip) {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { gz ->
                readBoundedBytes(gz, MAX_ARCHIVE_BYTES)
            }
        } else bytes

        val rawBase = originalName.removeSuffix(".gz").removeSuffix(".GZ").removeSuffix(".zip").removeSuffix(".ZIP")
        val parsedExt = rawBase.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val ext = parsedExt.takeIf { it in subtitleExtensions } ?: "srt"
        val stemRaw = if (parsedExt in subtitleExtensions) rawBase.substringBeforeLast('.') else rawBase
        val cleanStem = sanitizeStem(stemRaw).ifBlank { "subtitle" }
        val targetName = if (!preferredPrefix.isNullOrBlank() && !cleanStem.startsWith(preferredPrefix, ignoreCase = true)) {
            "${sanitizeStem(preferredPrefix)}.${cleanStem}.$ext"
        } else {
            "$cleanStem.$ext"
        }
        val target = uniqueFile(destDir, targetName)
        target.writeBytes(finalBytes)
        return listOf(target)
    }

    private fun readBoundedBytes(input: InputStream, maxBytes: Int): ByteArray {
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) error("Subtitle archive exceeds ${maxBytes / (1024 * 1024)} MB limit")
            baos.write(buf, 0, n)
        }
        return baos.toByteArray()
    }

    internal fun sanitizeStem(raw: String): String =
        raw.replace(Regex("""[^a-zA-Z0-9._ -]"""), "_").trim().take(80)

    internal fun uniqueFile(dir: File, desiredName: String): File {
        val direct = File(dir, desiredName)
        if (!direct.exists()) return direct
        val stem = desiredName.substringBeforeLast('.')
        val ext = desiredName.substringAfterLast('.', "srt")
        for (i in 1..99) {
            val alt = File(dir, "${stem}_$i.$ext")
            if (!alt.exists()) return alt
        }
        return File(dir, "${stem}_${System.currentTimeMillis()}.$ext")
    }

    fun parseSearchResponse(jsonString: String): List<SubtitleCandidate> {
        val root = JsonLite.parseObject(jsonString)
        val data = root["data"] as? List<*> ?: return emptyList()
        val out = mutableListOf<SubtitleCandidate>()

        for (entry in data) {
            val obj = entry as? Map<*, *> ?: continue
            val attrs = obj["attributes"] as? Map<*, *> ?: continue
            val files = attrs["files"] as? List<*> ?: continue
            val firstFile = files.firstOrNull() as? Map<*, *> ?: continue
            val fileId = (firstFile["file_id"] as? Number)?.toInt() ?: continue
            val fileName = firstFile["file_name"]?.toString()?.takeIf { it.isNotBlank() } ?: "subtitle_$fileId.srt"
            val release = attrs["release"]?.toString()?.takeIf { it.isNotBlank() } ?: fileName
            val language = attrs["language"]?.toString()?.uppercase(Locale.ROOT).orEmpty()
            val downloadCount = (attrs["download_count"] as? Number)?.toInt() ?: 0
            val fps = (attrs["fps"] as? Number)?.toDouble() ?: 0.0
            val hashMatch = attrs["moviehash_match"] as? Boolean ?: false
            val hi = attrs["hearing_impaired"] as? Boolean ?: false
            val uploaderObj = attrs["uploader"] as? Map<*, *>
            val uploader = uploaderObj?.get("name")?.toString().orEmpty()

            out += SubtitleCandidate(
                fileId = fileId,
                fileName = fileName,
                release = release,
                language = language,
                downloadCount = downloadCount,
                fps = fps,
                hashMatch = hashMatch,
                uploader = uploader,
                hearingImpaired = hi,
            )
        }
        return out.sortedWith(
            compareByDescending<SubtitleCandidate> { it.hashMatch }
                .thenByDescending { it.downloadCount }
        )
    }

    internal fun escapeJson(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    /**
     * Zero-dependency pure Kotlin JSON parser (works cleanly in both Android runtime and JVM unit tests).
     */
    internal object JsonLite {
        fun parseObject(json: String): Map<String, Any?> {
            val p = Parser(json)
            val v = p.parseValue()
            @Suppress("UNCHECKED_CAST")
            return (v as? Map<String, Any?>) ?: emptyMap()
        }

        fun parseArray(json: String): List<Any?> {
            val p = Parser(json)
            val v = p.parseValue()
            @Suppress("UNCHECKED_CAST")
            return (v as? List<Any?>) ?: emptyList()
        }

        fun parseAny(json: String): Any? = Parser(json).parseValue()

        private class Parser(private val s: String) {
            private var i = 0

            private fun skipWs() {
                while (i < s.length && s[i].isWhitespace()) i++
            }

            fun parseValue(): Any? {
                skipWs()
                if (i >= s.length) return null
                return when (val c = s[i]) {
                    '{' -> parseObj()
                    '[' -> parseArr()
                    '"' -> parseStr()
                    't' -> { i += 4; true }
                    'f' -> { i += 5; false }
                    'n' -> { i += 4; null }
                    else -> if (c == '-' || c.isDigit()) parseNum() else null
                }
            }

            private fun parseObj(): Map<String, Any?> {
                val map = LinkedHashMap<String, Any?>()
                i++ // '{'
                skipWs()
                if (i < s.length && s[i] == '}') { i++; return map }
                while (i < s.length) {
                    skipWs()
                    val key = parseStr()
                    skipWs()
                    if (i < s.length && s[i] == ':') i++
                    map[key] = parseValue()
                    skipWs()
                    if (i < s.length && s[i] == ',') { i++; continue }
                    if (i < s.length && s[i] == '}') { i++; break }
                    break
                }
                return map
            }

            private fun parseArr(): List<Any?> {
                val list = ArrayList<Any?>()
                i++ // '['
                skipWs()
                if (i < s.length && s[i] == ']') { i++; return list }
                while (i < s.length) {
                    list.add(parseValue())
                    skipWs()
                    if (i < s.length && s[i] == ',') { i++; continue }
                    if (i < s.length && s[i] == ']') { i++; break }
                    break
                }
                return list
            }

            private fun parseStr(): String {
                if (i >= s.length || s[i] != '"') return ""
                i++
                val sb = StringBuilder()
                while (i < s.length) {
                    val c = s[i++]
                    if (c == '"') return sb.toString()
                    if (c == '\\' && i < s.length) {
                        when (val esc = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 <= s.length) {
                                    val code = s.substring(i, i + 4).toIntOrNull(16) ?: 32
                                    sb.append(code.toChar())
                                    i += 4
                                }
                            }
                            else -> sb.append(esc)
                        }
                    } else {
                        sb.append(c)
                    }
                }
                return sb.toString()
            }

            private fun parseNum(): Number {
                val start = i
                if (s[i] == '-') i++
                while (i < s.length && s[i].isDigit()) i++
                var isFloat = false
                if (i < s.length && s[i] == '.') {
                    isFloat = true
                    i++
                    while (i < s.length && s[i].isDigit()) i++
                }
                if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                    isFloat = true
                    i++
                    if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                    while (i < s.length && s[i].isDigit()) i++
                }
                val numStr = s.substring(start, i)
                return if (isFloat) {
                    numStr.toDoubleOrNull() ?: 0.0
                } else {
                    numStr.toLongOrNull() ?: numStr.toDoubleOrNull() ?: 0L
                }
            }
        }
    }
}

