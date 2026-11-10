package app.msplayer.offline

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Cloud Subtitle Translator adapted from SRM_TAMIL_SUB architecture.
 * Locks original timestamps via cue-ID JSON batches (35 cues/batch), strips HTML/BOM/ASS tags,
 * streams Batch 0 live, cascades Groq Llama 3.3 70B -> Llama 3.1 8B -> free GTX fallback,
 * and saves UTF-8 BOM `<title>.<en|ta>.srt`.
 */
object SubtitleTranslator {

    const val GROQ_CHAT_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
    private const val MODEL_PRIMARY = "llama-3.3-70b-versatile"
    private const val MODEL_FAST_FALLBACK = "llama-3.1-8b-instant"
    const val BATCH_SIZE = 35

    enum class TargetLanguage(val code: String, val label: String) {
        ENGLISH("en", "English"),
        TAMIL("ta", "Tamil"),
    }

    data class TranslationResult(
        val cues: List<PlaybackRules.SubtitleCue>,
        val savedFile: File?,
        val engineLabel: String,
    )

    fun cleanCueText(raw: String): String {
        return raw
            .replace("\uFEFF", "")
            .replace("\r", "")
            .replace(Regex("""\{[^}]*\}"""), "")
            .replace(Regex("""<[^>]+>"""), "")
            .replace("\\N", "\n")
            .replace("\\n", "\n")
            .lines()
            .joinToString("\n") { line -> line.replace(Regex("""\s+"""), " ").trim() }
            .trim()
    }

    fun prepareCuesForTranslation(cues: List<PlaybackRules.SubtitleCue>): List<PlaybackRules.SubtitleCue> {
        return cues.mapNotNull { cue ->
            val cleaned = cleanCueText(cue.text)
            if (cleaned.isBlank()) null else cue.copy(text = cleaned)
        }.sortedBy { it.startMs }
    }

    fun buildChatPayload(
        model: String,
        batch: List<IndexedValue<PlaybackRules.SubtitleCue>>,
        target: TargetLanguage,
    ): String {
        val systemPrompt = when (target) {
            TargetLanguage.TAMIL ->
                "You translate film subtitles into natural conversational Tamil. Keep tone and names accurate. Return ONLY a JSON array of objects with exact \"id\" and translated \"t\" strings, preserving item count and ids."
            TargetLanguage.ENGLISH ->
                "You translate film subtitles into natural idiomatic English. Preserve speaker meaning and concise subtitle pacing. Return ONLY a JSON array of objects with exact \"id\" and translated \"t\" strings, preserving item count and ids."
        }
        val itemsJson = batch.joinToString(",", prefix = "[", postfix = "]") { (id, cue) ->
            """{"id":$id,"t":"${OpenSubtitlesClient.escapeJson(cue.text.replace('\n', ' '))}"}"""
        }
        return """{"model":"${OpenSubtitlesClient.escapeJson(model)}","temperature":0.2,"messages":[{"role":"system","content":"${OpenSubtitlesClient.escapeJson(systemPrompt)}"},{"role":"user","content":"Translate every entry to ${target.label}: ${OpenSubtitlesClient.escapeJson(itemsJson)}"}]}"""
    }

    fun parseTranslatedBatch(rawContent: String, expectedIds: Set<Int>): Map<Int, String> {
        var cleaned = rawContent.trim()
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.substringAfter('\n').substringBeforeLast("```").trim()
        }
        val firstBracket = cleaned.indexOf('[')
        val firstBrace = cleaned.indexOf('{')
        val candidateJson = when {
            firstBracket >= 0 && (firstBrace < 0 || firstBracket < firstBrace) ->
                cleaned.substring(firstBracket, (cleaned.lastIndexOf(']') + 1).coerceAtLeast(firstBracket + 1))
            firstBrace >= 0 ->
                cleaned.substring(firstBrace, (cleaned.lastIndexOf('}') + 1).coerceAtLeast(firstBrace + 1))
            else -> cleaned
        }

        val parsed = OpenSubtitlesClient.JsonLite.parseAny(candidateJson) ?: return emptyMap()
        val arrayList: List<*> = when (parsed) {
            is List<*> -> parsed
            is Map<*, *> -> (parsed["translations"] as? List<*>)
                ?: (parsed["items"] as? List<*>)
                ?: (parsed["subtitles"] as? List<*>)
                ?: (parsed["data"] as? List<*>)
                ?: emptyList<Any?>()
            else -> emptyList<Any?>()
        }

        val out = LinkedHashMap<Int, String>()
        for (entry in arrayList) {
            val map = entry as? Map<*, *> ?: continue
            val id = (map["id"] as? Number)?.toInt() ?: map["id"]?.toString()?.toIntOrNull() ?: continue
            if (id !in expectedIds) continue
            val text = (map["t"] ?: map["text"] ?: map["translation"])?.toString()?.trim().orEmpty()
            if (text.isNotBlank()) {
                out[id] = text
            }
        }
        return out
    }

    fun balanceTranslatedLine(text: String, maxCharsPerLine: Int = AutoSubEngine.MAX_CHARS_PER_LINE): String {
        val single = text.replace('\n', ' ').replace(Regex("""\s+"""), " ").trim()
        if (single.length <= maxCharsPerLine) return single
        val words = single.split(' ').filter { it.isNotBlank() }
        if (words.size <= 2) return single
        val splitIdx = AutoSubEngine.findBestSplit(words, maxCharsPerLine)
        val line1 = words.subList(0, splitIdx).joinToString(" ")
        val line2 = words.subList(splitIdx, words.size).joinToString(" ")
        return if (line2.isNotBlank()) "$line1\n$line2" else line1
    }

    fun translateCues(
        context: Context,
        cues: List<PlaybackRules.SubtitleCue>,
        target: TargetLanguage,
        videoTitle: String,
        videoFile: File? = null,
        groqKeyRaw: String = SpeechToTextClient.activeGroqKey(context),
        isCancelled: (() -> Boolean)? = null,
        onProgress: ((String, Int) -> Unit)? = null,
        onPartialCues: ((List<PlaybackRules.SubtitleCue>) -> Unit)? = null,
    ): TranslationResult {
        val sourceCues = prepareCuesForTranslation(cues)
        if (sourceCues.isEmpty()) error("No subtitle text available to translate")

        val keys = SpeechToTextClient.parseApiKeys(groqKeyRaw)
        val indexed = sourceCues.withIndex().toList()
        val batches = indexed.chunked(BATCH_SIZE)
        val translatedMap = LinkedHashMap<Int, String>(sourceCues.size)
        val disabledKeys = HashSet<String>()
        var usedFallback = false

        batches.forEachIndexed { batchIdx, batch ->
            if (isCancelled?.invoke() == true) error("Cancelled")
            val pct = 8 + ((batchIdx.toDouble() / batches.size.coerceAtLeast(1)) * 84.0).toInt().coerceIn(0, 84)
            onProgress?.invoke("Translating to ${target.label} (${batchIdx + 1}/${batches.size})...", pct)

            var resolved = emptyMap<Int, String>()
            val expectedIds = batch.map { it.index }.toSet()

            if (keys.isNotEmpty()) {
                val activeKeys = keys.filter { it !in disabledKeys }
                keyLoop@ for (kIdx in activeKeys.indices) {
                    val key = activeKeys[(batchIdx + kIdx) % activeKeys.size]
                    for (model in listOf(MODEL_PRIMARY, MODEL_FAST_FALLBACK)) {
                        try {
                            val responseContent = callGroqChat(key, buildChatPayload(model, batch, target))
                            val parsed = parseTranslatedBatch(responseContent, expectedIds)
                            if (parsed.size >= (batch.size * 0.7).toInt().coerceAtLeast(1)) {
                                resolved = parsed
                                break@keyLoop
                            }
                        } catch (e: Exception) {
                            val msg = e.message?.lowercase(Locale.ROOT).orEmpty()
                            if (msg.contains("401") || msg.contains("403")) {
                                disabledKeys += key
                                break
                            }
                            if (msg.contains("429") && model == MODEL_PRIMARY) {
                                continue // Immediately drop to high-headroom 8b-instant before failing key
                            }
                        }
                    }
                }
            }

            if (resolved.size < batch.size) {
                val missing = batch.filter { it.index !in resolved }
                val gtxMap = runCatching { translateBatchWithGtx(missing, target) }.getOrDefault(emptyMap())
                if (gtxMap.isNotEmpty()) {
                    usedFallback = true
                    resolved = resolved + gtxMap
                }
            }

            for ((id, cue) in batch) {
                val rawOut = resolved[id] ?: cue.text
                translatedMap[id] = balanceTranslatedLine(rawOut)
            }

            if (batchIdx == 0 && batches.size > 1 && onPartialCues != null) {
                val live = buildMergedCues(sourceCues, translatedMap)
                onPartialCues.invoke(live)
            }
        }

        val finalCues = buildMergedCues(sourceCues, translatedMap)
        onProgress?.invoke("Saving ${target.label} subtitles...", 95)
        val saved = SpeechToTextClient.saveSrtFile(
            context = context,
            videoTitle = videoTitle,
            cues = finalCues,
            videoFile = videoFile,
            langSuffix = target.code,
        )
        val engine = if (keys.isNotEmpty() && !usedFallback) "Cloud (${target.label})" else "Cloud Translate (${target.label})"
        return TranslationResult(finalCues, saved, engine)
    }

    private fun buildMergedCues(
        source: List<PlaybackRules.SubtitleCue>,
        translated: Map<Int, String>,
    ): List<PlaybackRules.SubtitleCue> {
        return source.mapIndexed { idx, cue ->
            cue.copy(text = translated[idx] ?: cue.text)
        }
    }

    private fun callGroqChat(apiKey: String, jsonPayload: String): String {
        val conn = (URL(GROQ_CHAT_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doInput = true
            doOutput = true
            connectTimeout = 20000
            readTimeout = 45000
            setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", OpenSubtitlesClient.USER_AGENT)
        }
        conn.outputStream.use { it.write(jsonPayload.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val body = if (code in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            error("HTTP $code: ${err.take(100)}")
        }
        val root = OpenSubtitlesClient.JsonLite.parseObject(body)
        val choices = root["choices"] as? List<*> ?: error("No choices")
        val first = choices.firstOrNull() as? Map<*, *> ?: error("Empty choice")
        val msg = first["message"] as? Map<*, *> ?: error("No message")
        return msg["content"]?.toString() ?: ""
    }

    private fun translateBatchWithGtx(
        batch: List<IndexedValue<PlaybackRules.SubtitleCue>>,
        target: TargetLanguage,
    ): Map<Int, String> {
        if (batch.isEmpty()) return emptyMap()
        val out = LinkedHashMap<Int, String>(batch.size)
        // Translate small joined chunks with clear ASCII sentinel so timestamps remain invariant
        for (sub in batch.chunked(12)) {
            val joined = sub.joinToString("\n[[MS]]\n") { it.value.text.replace('\n', ' ') }
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=${target.code}&dt=t&q=${URLEncoder.encode(joined, "UTF-8")}"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 20000
                setRequestProperty("User-Agent", OpenSubtitlesClient.USER_AGENT)
            }
            if (conn.responseCode !in 200..299) continue
            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            val root = OpenSubtitlesClient.JsonLite.parseArray(raw)
            val segments = root.firstOrNull() as? List<*> ?: continue
            val combined = buildString {
                for (seg in segments) {
                    val pair = seg as? List<*> ?: continue
                    pair.firstOrNull()?.toString()?.let(::append)
                }
            }
            val parts = combined.split(Regex("""\[\[\s*MS\s*\]\]"""))
            sub.forEachIndexed { i, item ->
                val translated = parts.getOrNull(i)?.trim()
                if (!translated.isNullOrBlank()) {
                    out[item.index] = translated
                }
            }
        }
        return out
    }
}

