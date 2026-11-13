package app.msplayer.offline

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * TMDB-resolved multi-provider subtitle search & download engine.
 * Aggregates OpenSubtitles, SubDL, SubSource, and Wyzie Subs concurrently with
 * automatic Title / Year / SxxExx parsing and archive extraction.
 */
object OnlineSubtitlesClient {

    const val TMDB_API_BASE = "https://api.themoviedb.org/3"
    const val SUBDL_API_BASE = "https://api.subdl.com/api/v1"
    const val SUBDL_DL_BASE = "https://dl.subdl.com"
    const val SUBSOURCE_API_BASE = "https://api.subsource.net/api/v1"
    const val WYZIE_API_BASE = "https://sub.wyzie.io"

    val DEFAULT_SUBDL_KEY: String get() = BuildConfig.DEFAULT_SUBDL_KEY
    val DEFAULT_SUBSOURCE_KEY: String get() = BuildConfig.DEFAULT_SUBSOURCE_KEY
    val DEFAULT_WYZIE_KEY: String get() = BuildConfig.DEFAULT_WYZIE_KEY
    val DEFAULT_TMDB_KEY: String get() = BuildConfig.DEFAULT_TMDB_KEY

    enum class OnlineProvider(val label: String, val badge: String) {
        OPENSUBTITLES("OpenSubtitles", "OS"),
        SUBDL("SubDL", "SubDL"),
        SUBSOURCE("SubSource", "SubSrc"),
        WYZIE("Wyzie", "Wyzie"),
    }

    data class MediaHints(
        val title: String,
        val year: Int? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val isSeries: Boolean = season != null || episode != null,
    )

    data class TmdbMatch(
        val tmdbId: Int,
        val imdbId: String? = null,
        val title: String,
        val year: Int? = null,
        val mediaType: String = "movie", // "movie" or "tv"
    )

    data class OnlineSubtitleCandidate(
        val provider: OnlineProvider,
        val id: String,
        val fileName: String,
        val release: String,
        val language: String,
        val downloadCount: Int = 0,
        val fps: Double = 0.0,
        val hashMatch: Boolean = false,
        val hearingImpaired: Boolean = false,
        val uploader: String = "",
        val format: String = "srt",
        val downloadUrl: String = "",
        val openSubtitlesCandidate: OpenSubtitlesClient.SubtitleCandidate? = null,
        val season: Int? = null,
        val episode: Int? = null,
    )

    data class SearchSummary(
        val candidates: List<OnlineSubtitleCandidate>,
        val tmdbMatch: TmdbMatch? = null,
        val activeProviders: List<OnlineProvider> = emptyList(),
        val providerErrors: Map<OnlineProvider, String> = emptyMap(),
    )

    data class OnlineDownloadResult(
        val file: File,
        val allExtracted: List<File>,
        val provider: OnlineProvider,
        val remainingDownloads: Int? = null,
    )

    fun activeSubDlKey(context: Context): String =
        Store.prefs(context).getString("subdl_api_key", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_SUBDL_KEY

    fun saveSubDlKey(context: Context, key: String) = saveKeyPref(context, "subdl_api_key", key)

    fun activeSubSourceKey(context: Context): String =
        Store.prefs(context).getString("subsource_api_key", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_SUBSOURCE_KEY

    fun saveSubSourceKey(context: Context, key: String) = saveKeyPref(context, "subsource_api_key", key)

    fun activeWyzieKey(context: Context): String =
        Store.prefs(context).getString("wyzie_api_key", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_WYZIE_KEY

    fun saveWyzieKey(context: Context, key: String) = saveKeyPref(context, "wyzie_api_key", key)

    fun activeTmdbKey(context: Context): String =
        Store.prefs(context).getString("tmdb_api_key", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_TMDB_KEY

    fun saveTmdbKey(context: Context, key: String) = saveKeyPref(context, "tmdb_api_key", key)

    private fun saveKeyPref(context: Context, key: String, value: String) {
        val clean = value.trim()
        Store.prefs(context).edit().apply {
            if (clean.isEmpty()) remove(key) else putString(key, clean)
        }.apply()
    }

    /**
     * Parses clean media title, release year, season, and episode number from a raw filename or URL.
     */
    fun parseMediaHints(rawName: String): MediaHints {
        var text = rawName.trim()
        if (text.contains("://")) {
            val seg = text.substringBefore('?').substringBefore('#').substringAfterLast('/')
            text = runCatching { URLDecoder.decode(seg, "UTF-8") }.getOrDefault(seg)
        }
        text = text.replace(
            Regex("""\.(?:mkv|mp4|m4v|avi|webm|mov|ts|mts|m2ts|flv|wmv|srt|ass|ssa|vtt|zip)$""", RegexOption.IGNORE_CASE),
            ""
        )
        text = text.replace(Regex("""@\S+"""), " ")

        var season: Int? = null
        var episode: Int? = null
        var epMatchStart = -1

        val sxeRegex = Regex("""(?i)(?:^|[^a-z0-9])S(\d{1,2})[\s._-]*E(?:P)?(\d{1,3})(?:[^0-9]|$)""")
        val nxnRegex = Regex("""(?i)(?:^|[^a-z0-9])(\d{1,2})x(\d{1,3})(?:[^0-9]|$)""")
        val wordEpRegex = Regex("""(?i)(?:Season[\s._-]*(\d{1,2})[\s._-]*)?Episode[\s._-]*(\d{1,3})\b""")
        val epOnlyRegex = Regex("""(?i)(?:^|[\s._-])(?:EP|E)(\d{1,3})(?:[\s._-]|$)""")

        sxeRegex.find(text)?.let { m ->
            season = m.groupValues[1].toIntOrNull()
            episode = m.groupValues[2].toIntOrNull()
            epMatchStart = m.range.first
            text = text.removeRange(m.range)
        } ?: nxnRegex.find(text)?.let { m ->
            season = m.groupValues[1].toIntOrNull()
            episode = m.groupValues[2].toIntOrNull()
            epMatchStart = m.range.first
            text = text.removeRange(m.range)
        } ?: wordEpRegex.find(text)?.let { m ->
            season = m.groupValues[1].toIntOrNull() ?: 1
            episode = m.groupValues[2].toIntOrNull()
            epMatchStart = m.range.first
            text = text.removeRange(m.range)
        } ?: epOnlyRegex.find(text)?.let { m ->
            episode = m.groupValues[1].toIntOrNull()
            epMatchStart = m.range.first
            text = text.removeRange(m.range)
        }

        // Extract release year (prefer explicit (YYYY), otherwise last plausible standalone year token)
        var year: Int? = null
        val parenYear = Regex("""\(\s*((?:19|20)\d{2})\s*\)""").findAll(text).lastOrNull()
        if (parenYear != null) {
            year = parenYear.groupValues[1].toIntOrNull()
            text = text.removeRange(parenYear.range)
        } else {
            val normalizedForYear = text.replace(Regex("""[._]+"""), " ")
            val allYears = Regex("""\b((?:19\d{2}|20[0-3]\d))\b""").findAll(normalizedForYear).toList()
            val candidate = allYears.lastOrNull()
            if (candidate != null) {
                val parsed = candidate.groupValues[1].toIntOrNull()
                val before = normalizedForYear.substring(0, candidate.range.first).trim()
                if (parsed != null && before.isNotEmpty()) {
                    year = parsed
                    text = before
                }
            }
        }

        if (epMatchStart > 2 && text.length > epMatchStart) {
            val head = text.substring(0, epMatchStart).trim()
            if (head.length >= 2) text = head
        }

        val cleaned = OpenSubtitlesClient.cleanMediaQuery(text)
            .replace(Regex("""(?i)\b(?:S\d{1,2}\s*E\d{1,3}|Season\s*\d+|Episode\s*\d+)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .ifBlank { OpenSubtitlesClient.cleanMediaQuery(rawName) }

        return MediaHints(
            title = cleaned,
            year = year,
            season = season,
            episode = episode,
            isSeries = season != null || episode != null,
        )
    }

    /**
     * Resolves canonical TMDB ID & IMDb ID (`tt...`) from user query or parsed media hints.
     */
    fun resolveTmdb(tmdbKey: String, hints: MediaHints): TmdbMatch? {
        val cleanKey = tmdbKey.trim()
        val query = hints.title.trim()
        if (cleanKey.isBlank() || query.isBlank()) return null

        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$TMDB_API_BASE/search/multi?api_key=${URLEncoder.encode(cleanKey, "UTF-8")}&query=$encoded&include_adult=false"
        val searchJson = httpGet(searchUrl) ?: return null
        val match = parseTmdbSearch(searchJson, hints) ?: return null

        val extUrl = "$TMDB_API_BASE/${match.mediaType}/${match.tmdbId}/external_ids?api_key=${URLEncoder.encode(cleanKey, "UTF-8")}"
        val imdbId = httpGet(extUrl)?.let { parseTmdbExternalIds(it) }
        return match.copy(imdbId = imdbId)
    }

    fun parseTmdbSearch(json: String, hints: MediaHints): TmdbMatch? {
        val root = OpenSubtitlesClient.JsonLite.parseObject(json)
        val results = root["results"] as? List<*> ?: return null
        var best: TmdbMatch? = null
        var bestScore = Int.MIN_VALUE

        for ((index, raw) in results.withIndex()) {
            val obj = raw as? Map<*, *> ?: continue
            val mediaType = obj["media_type"]?.toString()?.lowercase(Locale.ROOT).orEmpty()
            if (mediaType != "movie" && mediaType != "tv") continue
            val id = (obj["id"] as? Number)?.toInt() ?: continue
            val title = (obj["title"] ?: obj["name"] ?: obj["original_title"] ?: obj["original_name"])?.toString()?.trim().orEmpty()
            if (title.isBlank()) continue
            val dateStr = (obj["release_date"] ?: obj["first_air_date"])?.toString().orEmpty()
            val itemYear = dateStr.take(4).toIntOrNull()
            val popularity = (obj["popularity"] as? Number)?.toDouble() ?: 0.0

            var score = (popularity.coerceAtMost(100.0)).toInt() - index * 5
            if (hints.isSeries && mediaType == "tv") score += 120
            if (!hints.isSeries && mediaType == "movie") score += 60
            if (hints.year != null && itemYear != null) {
                score += if (hints.year == itemYear) 100 else -kotlin.math.abs(hints.year - itemYear).coerceAtMost(40)
            }
            if (title.equals(hints.title, ignoreCase = true)) score += 90

            if (score > bestScore) {
                bestScore = score
                best = TmdbMatch(
                    tmdbId = id,
                    imdbId = null,
                    title = title,
                    year = itemYear,
                    mediaType = mediaType,
                )
            }
        }
        return best
    }

    fun parseTmdbExternalIds(json: String): String? {
        val root = OpenSubtitlesClient.JsonLite.parseObject(json)
        return root["imdb_id"]?.toString()?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }
    }

    fun searchWyzie(
        wyzieKey: String,
        tmdbId: Int?,
        imdbId: String?,
        languageCode2: String = "en",
        season: Int? = null,
        episode: Int? = null,
    ): List<OnlineSubtitleCandidate> {
        val targetId = tmdbId?.takeIf { it > 0 }?.toString()
            ?: imdbId?.trim()?.takeIf { it.isNotBlank() }
            ?: return emptyList()
        val key = wyzieKey.trim()
        val params = buildList {
            add("id=${URLEncoder.encode(targetId, "UTF-8")}")
            if (key.isNotBlank()) add("key=${URLEncoder.encode(key, "UTF-8")}")
            if (season != null && season > 0) add("season=$season")
            if (episode != null && episode > 0) add("episode=$episode")
            val cleanLang = languageCode2.trim().lowercase(Locale.ROOT)
            if (cleanLang.isNotBlank() && cleanLang != "all") add("language=${URLEncoder.encode(cleanLang, "UTF-8")}")
        }.joinToString("&")
        val json = httpGetOrThrow("$WYZIE_API_BASE/search?$params", providerName = "Wyzie")
        return parseWyzieResponse(json)
    }

    fun parseWyzieResponse(json: String): List<OnlineSubtitleCandidate> {
        val any = OpenSubtitlesClient.JsonLite.parseAny(json) ?: return emptyList()
        val items = when (any) {
            is List<*> -> any
            is Map<*, *> -> (any["subtitles"] as? List<*>) ?: (any["data"] as? List<*>) ?: (any["results"] as? List<*>) ?: emptyList<Any?>()
            else -> emptyList<Any?>()
        }
        val out = mutableListOf<OnlineSubtitleCandidate>()
        for (raw in items) {
            val obj = raw as? Map<*, *> ?: continue
            val dlUrl = (obj["url"] ?: obj["downloadUrl"] ?: obj["download_url"])?.toString()?.trim().orEmpty()
            if (dlUrl.isBlank()) continue
            val id = obj["id"]?.toString()?.takeIf { it.isNotBlank() } ?: dlUrl.hashCode().toString()
            val format = obj["format"]?.toString()?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() } ?: "srt"
            val lang = (obj["language"] ?: obj["display"])?.toString()?.uppercase(Locale.ROOT)?.take(8).orEmpty()
            val release = (obj["release"] ?: obj["fileName"] ?: obj["name"] ?: obj["display"])?.toString()?.trim().orEmpty()
            val baseName = release.ifBlank { "Wyzie_${lang.ifBlank { "sub" }}_$id" }
            val knownExts = OpenSubtitlesClient.subtitleExtensions + "zip"
            val hasKnownExt = baseName.substringAfterLast('.', "").lowercase(Locale.ROOT) in knownExts
            val fileName = if (hasKnownExt) baseName else "$baseName.$format"
            val hi = obj["isHearingImpaired"] as? Boolean ?: obj["hearingImpaired"] as? Boolean ?: false
            val src = obj["source"]?.toString()?.trim().orEmpty()
            out += OnlineSubtitleCandidate(
                provider = OnlineProvider.WYZIE,
                id = "wyzie:$id",
                fileName = fileName,
                release = release.ifBlank { fileName },
                language = lang.ifBlank { "SUB" },
                downloadCount = (obj["downloadCount"] as? Number)?.toInt() ?: 0,
                hearingImpaired = hi,
                uploader = src.ifBlank { "Wyzie" },
                format = format,
                downloadUrl = dlUrl,
                season = (obj["season"] as? Number)?.toInt(),
                episode = (obj["episode"] as? Number)?.toInt(),
            )
        }
        return out
    }

    fun searchSubDL(
        subDlKey: String,
        hints: MediaHints,
        tmdbId: Int? = null,
        imdbId: String? = null,
        languageCode2: String = "en",
    ): List<OnlineSubtitleCandidate> {
        val cleanKey = subDlKey.trim()
        if (cleanKey.isBlank()) error("SubDL API key required")
        val params = buildList {
            add("api_key=${URLEncoder.encode(cleanKey, "UTF-8")}")
            when {
                tmdbId != null && tmdbId > 0 -> add("tmdb_id=$tmdbId")
                !imdbId.isNullOrBlank() -> add("imdb_id=${URLEncoder.encode(imdbId.trim(), "UTF-8")}")
                hints.title.isNotBlank() -> add("film_name=${URLEncoder.encode(hints.title, "UTF-8")}")
                else -> error("Enter a title to search")
            }
            if (tmdbId == null && imdbId.isNullOrBlank() && hints.year != null) add("year=${hints.year}")
            if (hints.isSeries) {
                add("type=tv")
                if (hints.season != null && hints.season > 0) add("season_number=${hints.season}")
                if (hints.episode != null && hints.episode > 0) add("episode_number=${hints.episode}")
            }
            val langParam = toSubDlLanguageCode(languageCode2)
            if (langParam.isNotBlank()) add("languages=$langParam")
            add("subs_per_page=30")
        }.joinToString("&")
        val json = httpGetOrThrow("$SUBDL_API_BASE/subtitles?$params", providerName = "SubDL")
        return parseSubDlResponse(json)
    }

    internal fun toSubDlLanguageCode(code2: String): String = when (code2.trim().lowercase(Locale.ROOT)) {
        "", "all" -> ""
        "zh", "zh-cn", "zh-tw" -> "ZH"
        "pt-br" -> "BR_PT"
        else -> SubtitleTranslator.findLanguage(code2)?.code?.uppercase(Locale.ROOT)
            ?: code2.trim().uppercase(Locale.ROOT)
    }

    fun parseSubDlResponse(json: String): List<OnlineSubtitleCandidate> {
        val root = OpenSubtitlesClient.JsonLite.parseObject(json)
        val subs = root["subtitles"] as? List<*> ?: return emptyList()
        val out = mutableListOf<OnlineSubtitleCandidate>()
        for ((idx, raw) in subs.withIndex()) {
            val obj = raw as? Map<*, *> ?: continue
            val rawUrl = obj["url"]?.toString()?.trim().orEmpty()
            if (rawUrl.isBlank()) continue
            val fullUrl = when {
                rawUrl.startsWith("http", ignoreCase = true) -> rawUrl
                rawUrl.startsWith("/") -> "$SUBDL_DL_BASE$rawUrl"
                else -> "$SUBDL_DL_BASE/$rawUrl"
            }
            val release = (obj["release_name"] ?: obj["name"])?.toString()?.trim().orEmpty()
            val lang = (obj["language"] ?: obj["lang"])?.toString()?.uppercase(Locale.ROOT)?.take(8).orEmpty()
            val fileName = obj["name"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
                ?: release.ifBlank { "subdl_$idx" }
            val safeName = if (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT) in OpenSubtitlesClient.subtitleExtensions + "zip") {
                fileName
            } else {
                "$fileName.zip"
            }
            out += OnlineSubtitleCandidate(
                provider = OnlineProvider.SUBDL,
                id = "subdl:${fullUrl.substringAfterLast('/')}",
                fileName = safeName,
                release = release.ifBlank { safeName },
                language = lang.ifBlank { "SUB" },
                downloadCount = (obj["downloads"] as? Number)?.toInt() ?: 0,
                hearingImpaired = obj["hi"] as? Boolean ?: false,
                uploader = obj["author"]?.toString()?.trim().orEmpty(),
                format = if (safeName.endsWith(".zip", true)) "zip" else "srt",
                downloadUrl = fullUrl,
                season = (obj["season"] as? Number)?.toInt(),
                episode = (obj["episode"] as? Number)?.toInt(),
            )
        }
        return out
    }

    fun searchSubSource(
        subSourceKey: String,
        hints: MediaHints,
        imdbId: String? = null,
        languageCode2: String = "en",
    ): List<OnlineSubtitleCandidate> {
        val cleanKey = subSourceKey.trim()
        if (cleanKey.isBlank()) error("SubSource API key required")
        val headers = mapOf("X-API-Key" to cleanKey)

        var movieId: Int? = null
        if (!imdbId.isNullOrBlank()) {
            val imdbJson = runCatching {
                httpGetOrThrow(
                    "$SUBSOURCE_API_BASE/movies/search?searchType=imdb&imdb=${URLEncoder.encode(imdbId.trim(), "UTF-8")}",
                    headers = headers,
                    providerName = "SubSource",
                )
            }.getOrNull()
            if (imdbJson != null) {
                movieId = parseSubSourceFirstMovieId(imdbJson, hints)
            }
        }
        if (movieId == null && hints.title.isNotBlank()) {
            val textJson = httpGetOrThrow(
                "$SUBSOURCE_API_BASE/movies/search?searchType=text&q=${URLEncoder.encode(hints.title, "UTF-8")}",
                headers = headers,
                providerName = "SubSource",
            )
            movieId = parseSubSourceFirstMovieId(textJson, hints)
        }
        if (movieId == null) return emptyList()

        val langSlug = toSubSourceLanguageSlug(languageCode2)
        val subParams = buildList {
            add("movieId=$movieId")
            if (langSlug.isNotBlank()) add("language=$langSlug")
        }.joinToString("&")
        val subsJson = httpGetOrThrow(
            "$SUBSOURCE_API_BASE/subtitles?$subParams",
            headers = headers,
            providerName = "SubSource",
        )
        return parseSubSourceSubtitles(subsJson, languageCode2)
    }

    internal fun toSubSourceLanguageSlug(code2: String): String {
        val clean = code2.trim().lowercase(Locale.ROOT)
        if (clean.isEmpty() || clean == "all") return ""
        return SubtitleTranslator.findLanguage(clean)?.label?.lowercase(Locale.ROOT).orEmpty()
    }

    fun parseSubSourceFirstMovieId(json: String, hints: MediaHints): Int? {
        val root = OpenSubtitlesClient.JsonLite.parseObject(json)
        val data = (root["data"] as? List<*>) ?: (root["results"] as? List<*>) ?: return null
        var bestId: Int? = null
        var bestScore = Int.MIN_VALUE
        for ((idx, raw) in data.withIndex()) {
            val obj = raw as? Map<*, *> ?: continue
            val id = ((obj["movieId"] ?: obj["id"]) as? Number)?.toInt() ?: continue
            val title = (obj["title"] ?: obj["name"])?.toString()?.trim().orEmpty()
            val year = ((obj["releaseYear"] ?: obj["year"]) as? Number)?.toInt()
            val type = obj["type"]?.toString()?.lowercase(Locale.ROOT).orEmpty()
            var score = 50 - idx * 5
            if (title.equals(hints.title, ignoreCase = true)) score += 80
            if (hints.year != null && year == hints.year) score += 60
            if (hints.isSeries && (type.contains("tv") || type.contains("series"))) score += 40
            if (score > bestScore) {
                bestScore = score
                bestId = id
            }
        }
        return bestId
    }

    fun parseSubSourceSubtitles(json: String, languageFilterCode2: String = ""): List<OnlineSubtitleCandidate> {
        val root = OpenSubtitlesClient.JsonLite.parseObject(json)
        val data = (root["data"] as? List<*>) ?: (root["subtitles"] as? List<*>) ?: return emptyList()
        val expectedSlug = toSubSourceLanguageSlug(languageFilterCode2)
        val out = mutableListOf<OnlineSubtitleCandidate>()

        for (raw in data) {
            val obj = raw as? Map<*, *> ?: continue
            val subId = ((obj["subtitleId"] ?: obj["id"]) as? Number)?.toInt() ?: continue
            val rawLang = obj["language"]?.toString()?.trim().orEmpty()
            if (expectedSlug.isNotBlank() &&
                rawLang.isNotBlank() &&
                !rawLang.equals(expectedSlug, ignoreCase = true) &&
                !rawLang.equals(languageFilterCode2, ignoreCase = true)
            ) {
                continue
            }
            val relList = (obj["releaseInfo"] as? List<*>)?.mapNotNull { it?.toString()?.trim() }?.filter { it.isNotBlank() }.orEmpty()
            val release = relList.firstOrNull()
                ?: (obj["releaseName"] ?: obj["title"] ?: obj["name"])?.toString()?.trim().orEmpty()
            val safeName = (release.ifBlank { "subsource_$subId" }).let {
                if (it.substringAfterLast('.', "").lowercase(Locale.ROOT) in OpenSubtitlesClient.subtitleExtensions + "zip") it else "$it.zip"
            }
            val directUrl = (obj["downloadUrl"] ?: obj["download_url"] ?: obj["link"])?.toString()?.trim().orEmpty()
            val dlUrl = if (directUrl.startsWith("http", true)) directUrl else "$SUBSOURCE_API_BASE/subtitles/$subId/download"
            val langBadge = SubtitleTranslator.findLanguage(rawLang)?.code?.uppercase(Locale.ROOT)
                ?: rawLang.uppercase(Locale.ROOT).take(6).ifBlank { "SUB" }
            val contributor = (obj["contributor"] as? Map<*, *>)?.get("displayname")?.toString()
                ?: obj["uploader"]?.toString()
                ?: ""
            out += OnlineSubtitleCandidate(
                provider = OnlineProvider.SUBSOURCE,
                id = "subsource:$subId",
                fileName = safeName,
                release = release.ifBlank { safeName },
                language = langBadge,
                downloadCount = (obj["downloads"] as? Number)?.toInt() ?: (obj["downloadCount"] as? Number)?.toInt() ?: 0,
                hearingImpaired = obj["hearingImpaired"] as? Boolean ?: false,
                uploader = contributor,
                format = if (safeName.endsWith(".zip", true)) "zip" else "srt",
                downloadUrl = dlUrl,
                season = (obj["season"] as? Number)?.toInt(),
                episode = (obj["episode"] as? Number)?.toInt(),
            )
        }
        return out
    }

    /**
     * Searches TMDB (for canonical IDs) and runs enabled providers concurrently.
     */
    fun searchAll(
        context: Context,
        rawTitleOrQuery: String,
        languageCode2: String = "en",
        movieHash: String? = null,
        enabledProviders: Set<OnlineProvider> = OnlineProvider.entries.toSet(),
        useTmdbResolution: Boolean = true,
    ): SearchSummary {
        val hints = parseMediaHints(rawTitleOrQuery)
        val tmdbKey = activeTmdbKey(context)
        val tmdbMatch = if (useTmdbResolution && tmdbKey.isNotBlank() && hints.title.isNotBlank()) {
            runCatching { resolveTmdb(tmdbKey, hints) }.getOrNull()
        } else null

        val searchTitle = tmdbMatch?.title?.takeIf { it.isNotBlank() } ?: hints.title
        val resolvedHints = hints.copy(
            title = searchTitle,
            year = hints.year ?: tmdbMatch?.year,
            isSeries = hints.isSeries || tmdbMatch?.mediaType == "tv",
        )

        val osKey = OpenSubtitlesClient.activeApiKey(context)
        val subDlKey = activeSubDlKey(context)
        val subSourceKey = activeSubSourceKey(context)
        val wyzieKey = activeWyzieKey(context)

        val tasks = linkedMapOf<OnlineProvider, Callable<List<OnlineSubtitleCandidate>>>()
        if (OnlineProvider.OPENSUBTITLES in enabledProviders && osKey.isNotBlank()) {
            tasks[OnlineProvider.OPENSUBTITLES] = Callable {
                OpenSubtitlesClient.searchSubtitles(
                    apiKey = osKey,
                    query = searchTitle,
                    languageCode2 = languageCode2,
                    movieHash = movieHash,
                    tmdbId = tmdbMatch?.tmdbId,
                    imdbId = tmdbMatch?.imdbId,
                    seasonNumber = resolvedHints.season,
                    episodeNumber = resolvedHints.episode,
                ).map { c ->
                    OnlineSubtitleCandidate(
                        provider = OnlineProvider.OPENSUBTITLES,
                        id = "os:${c.fileId}",
                        fileName = c.fileName,
                        release = c.release,
                        language = c.language,
                        downloadCount = c.downloadCount,
                        fps = c.fps,
                        hashMatch = c.hashMatch,
                        hearingImpaired = c.hearingImpaired,
                        uploader = c.uploader,
                        format = c.fileName.substringAfterLast('.', "srt").lowercase(Locale.ROOT),
                        openSubtitlesCandidate = c,
                    )
                }
            }
        }
        if (OnlineProvider.SUBDL in enabledProviders && subDlKey.isNotBlank()) {
            tasks[OnlineProvider.SUBDL] = Callable {
                searchSubDL(
                    subDlKey = subDlKey,
                    hints = resolvedHints,
                    tmdbId = tmdbMatch?.tmdbId,
                    imdbId = tmdbMatch?.imdbId,
                    languageCode2 = languageCode2,
                )
            }
        }
        if (OnlineProvider.SUBSOURCE in enabledProviders && subSourceKey.isNotBlank()) {
            tasks[OnlineProvider.SUBSOURCE] = Callable {
                searchSubSource(
                    subSourceKey = subSourceKey,
                    hints = resolvedHints,
                    imdbId = tmdbMatch?.imdbId,
                    languageCode2 = languageCode2,
                )
            }
        }
        if (OnlineProvider.WYZIE in enabledProviders && (tmdbMatch != null || wyzieKey.isNotBlank())) {
            tasks[OnlineProvider.WYZIE] = Callable {
                searchWyzie(
                    wyzieKey = wyzieKey,
                    tmdbId = tmdbMatch?.tmdbId,
                    imdbId = tmdbMatch?.imdbId,
                    languageCode2 = languageCode2,
                    season = resolvedHints.season,
                    episode = resolvedHints.episode,
                )
            }
        }

        val allCandidates = mutableListOf<OnlineSubtitleCandidate>()
        val errors = linkedMapOf<OnlineProvider, String>()

        if (tasks.isNotEmpty()) {
            val executor = Executors.newFixedThreadPool(tasks.size.coerceIn(1, 4))
            try {
                val futures = tasks.mapValues { (_, call) -> executor.submit(call) }
                for ((provider, future) in futures) {
                    try {
                        allCandidates += future.get(22, TimeUnit.SECONDS)
                    } catch (e: Exception) {
                        val rootMsg = (e.cause?.message ?: e.message ?: "Request failed").take(120)
                        errors[provider] = rootMsg
                    }
                }
            } finally {
                executor.shutdownNow()
            }
        }

        val ranked = rankAndDeduplicate(allCandidates, resolvedHints, rawTitleOrQuery)
        return SearchSummary(
            candidates = ranked,
            tmdbMatch = tmdbMatch,
            activeProviders = tasks.keys.toList(),
            providerErrors = errors,
        )
    }

    fun rankAndDeduplicate(
        candidates: List<OnlineSubtitleCandidate>,
        hints: MediaHints,
        rawVideoName: String = "",
    ): List<OnlineSubtitleCandidate> {
        val rawTokens = rawVideoName.lowercase(Locale.ROOT)
            .split(Regex("""[^a-z0-9]+"""))
            .filter { it.length >= 3 }
            .toSet()

        fun episodeMatches(c: OnlineSubtitleCandidate): Int {
            if (hints.episode == null) return 0
            if (c.season != null && c.episode != null) {
                return if (c.season == (hints.season ?: 1) && c.episode == hints.episode) 200 else -120
            }
            val rel = "${c.release} ${c.fileName}".lowercase(Locale.ROOT)
            val s = hints.season ?: 1
            val e = hints.episode
            val sPad = s.toString().padStart(2, '0')
            val ePad = e.toString().padStart(2, '0')
            return when {
                Regex("""\bs0?$s\s*e0?$e\b""").containsMatchIn(rel) -> 200
                Regex("""\b$s?x0?$e\b""").containsMatchIn(rel) -> 180
                rel.contains("s${sPad}e${ePad}") -> 200
                Regex("""\be(?:p)?0?$e\b""").containsMatchIn(rel) -> 110
                else -> 0
            }
        }

        fun tokenAffinity(c: OnlineSubtitleCandidate): Int {
            if (rawTokens.isEmpty()) return 0
            val rel = "${c.release} ${c.fileName}".lowercase(Locale.ROOT)
            var hits = 0
            for (tok in rawTokens) {
                if (tok in setOf("480p", "720p", "1080p", "2160p", "web", "webrip", "web-dl", "bluray", "hdtv", "x264", "x265", "hevc", "dual") &&
                    rel.contains(tok)
                ) {
                    hits += 25
                }
            }
            return hits.coerceAtMost(80)
        }

        return candidates
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<OnlineSubtitleCandidate> { candidate ->
                    var score = 0
                    if (candidate.hashMatch) score += 500
                    score += episodeMatches(candidate)
                    score += tokenAffinity(candidate)
                    score += (candidate.downloadCount / 100).coerceAtMost(60)
                    score
                }.thenBy { it.provider.ordinal }
            )
    }

    fun downloadCandidate(
        context: Context,
        candidate: OnlineSubtitleCandidate,
        videoTitle: String,
        videoFile: File? = null,
    ): OnlineDownloadResult {
        if (candidate.provider == OnlineProvider.OPENSUBTITLES && candidate.openSubtitlesCandidate != null) {
            val osKey = OpenSubtitlesClient.activeApiKey(context)
            val res = OpenSubtitlesClient.downloadSubtitle(
                context = context,
                apiKey = osKey,
                candidate = candidate.openSubtitlesCandidate,
                videoTitle = videoTitle,
                videoFile = videoFile,
                bearerToken = OpenSubtitlesClient.activeToken(context),
            )
            return OnlineDownloadResult(res.file, res.allExtracted, OnlineProvider.OPENSUBTITLES, res.remainingDownloads)
        }

        var url = candidate.downloadUrl.trim()
        if (url.isBlank()) error("Download link unavailable")

        val extraHeaders = when (candidate.provider) {
            OnlineProvider.SUBSOURCE -> mapOf("X-API-Key" to activeSubSourceKey(context))
            else -> emptyMap()
        }

        var bytes = httpGetBytes(url, extraHeaders)
        // Some SubSource download endpoints return JSON { "link": "..." } or { "downloadUrl": "..." }
        if (bytes.isNotEmpty() && bytes.size < 2048 && bytes[0] == '{'.code.toByte()) {
            val text = String(bytes, Charsets.UTF_8).trim()
            if (text.startsWith("{")) {
                val obj = OpenSubtitlesClient.JsonLite.parseObject(text)
                val redirect = (obj["link"] ?: obj["downloadUrl"] ?: obj["download_url"] ?: obj["url"])?.toString()?.trim()
                if (!redirect.isNullOrBlank()) {
                    url = redirect
                    bytes = httpGetBytes(url, emptyMap())
                }
            }
        }
        if (bytes.isEmpty()) error("${candidate.provider.label} returned empty subtitle file")

        val destDir = OpenSubtitlesClient.chooseDestinationDir(context, videoFile)
        val extracted = OpenSubtitlesClient.extractSubtitleFromStream(
            input = ByteArrayInputStream(bytes),
            originalName = candidate.fileName,
            destDir = destDir,
            preferredPrefix = OpenSubtitlesClient.sanitizeStem(videoTitle.substringBeforeLast('.')),
        )
        if (extracted.isEmpty()) error("No .srt/.ass/.vtt found in ${candidate.provider.label} download")
        return OnlineDownloadResult(extracted.first(), extracted, candidate.provider, null)
    }

    private fun httpGet(urlStr: String, headers: Map<String, String> = emptyMap()): String? =
        runCatching { httpGetOrThrow(urlStr, headers, "HTTP") }.getOrNull()

    private fun httpGetOrThrow(
        urlStr: String,
        headers: Map<String, String> = emptyMap(),
        providerName: String = "Online",
    ): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("User-Agent", OpenSubtitlesClient.USER_AGENT)
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> if (v.isNotBlank()) setRequestProperty(k, v) }
        }
        val code = conn.responseCode
        if (code in 200..299) {
            return conn.inputStream.bufferedReader().use { it.readText() }
        }
        val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        when (code) {
            401, 403 -> error("$providerName key unauthorized ($code)")
            429 -> error("$providerName rate limit ($code)")
            else -> error("$providerName HTTP $code${err.take(90).let { if (it.isNotBlank()) ": $it" else "" }}")
        }
    }

    private fun httpGetBytes(urlStr: String, headers: Map<String, String> = emptyMap()): ByteArray {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 15000
            readTimeout = 25000
            setRequestProperty("User-Agent", OpenSubtitlesClient.USER_AGENT)
            headers.forEach { (k, v) -> if (v.isNotBlank()) setRequestProperty(k, v) }
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            error("Download failed (HTTP $code)")
        }
        return conn.inputStream.use { it.readBytes() }
    }
}
