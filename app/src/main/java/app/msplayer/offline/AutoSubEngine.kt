package app.msplayer.offline

import java.util.Locale
import kotlin.math.max

/**
 * Autonomous Subtitle Timing & Balancing Engine.
 * Faithfully ports the SRM_AUTOSUB_BOT (bot_CB.py / bot_KG.py) timing algorithms,
 * linguistic line balancing, oversized-word splitting, and CPS extension rules.
 */
object AutoSubEngine {

    const val MAX_CHARS_PER_LINE: Int = 38
    const val MAX_CPS: Double = 20.0
    const val CPS_EXTENSION_LIMIT: Double = 1.5
    const val MIN_DURATION: Double = 1.0
    const val MAX_DURATION: Double = 7.0
    const val FRAME_GAP: Double = 0.08      // 80ms gap between subtitle blocks
    const val MAX_SILENCE_GAP: Double = 0.4  // 400ms gap triggers block break
    const val SUBTITLE_OFFSET: Double = 0.0
    const val END_PADDING: Double = 0.1

    data class WordInfo(
        val word: String,
        val start: Double,
        val end: Double
    )

    data class SubtitleBlock(
        val start: Double,
        val end: Double,
        val text: String
    )

    private val CONJUNCTIONS = setOf("and", "but", "because", "or", "so", "for", "nor", "yet")

    private val WEAK_WORDS = setOf(
        "that", "to", "of", "for", "with", "on", "at", "by", "from", "up",
        "into", "upon", "without", "through", "during", "including", "within",
        "which", "whose", "whom", "where", "when", "why", "how", "than", "as", "like"
    )

    /**
     * Splits an oversized word into hyphenated chunks of at most maxLen characters each.
     * Apportions timing proportionally to character span within the actual word window.
     */
    fun splitOversizedWord(word: String, wordStart: Double, wordEnd: Double, maxLen: Int = MAX_CHARS_PER_LINE): List<WordInfo> {
        val totalLen = word.length
        if (totalLen <= maxLen) {
            return listOf(WordInfo(word, wordStart, wordEnd))
        }

        val duration = max(wordEnd - wordStart, 0.001)
        val parts = mutableListOf<WordInfo>()
        var pos = 0

        while (totalLen - pos > maxLen) {
            val chunkLen = maxLen - 1 // reserve 1 char for the hyphen
            val chunkText = word.substring(pos, pos + chunkLen) + "-"
            val fracStart = pos.toDouble() / totalLen
            val fracEnd = (pos + chunkLen).toDouble() / totalLen
            val chunkStart = wordStart + duration * fracStart
            val chunkEnd = wordStart + duration * fracEnd
            parts.add(WordInfo(chunkText, chunkStart, chunkEnd))
            pos += chunkLen
        }

        // Final remaining chunk (unhyphenated)
        val finalChunk = word.substring(pos)
        val fracStart = pos.toDouble() / totalLen
        val chunkStart = wordStart + duration * fracStart
        parts.add(WordInfo(finalChunk, chunkStart, wordEnd))

        return parts
    }

    /**
     * Determines the optimal split index between lines for balanced readability.
     * Priority order:
     * 1. Sentence-ending punctuation (. ? !) with remainder >= 3 words
     * 2. Comma/semicolon with remainder >= 3 words
     * 3. Conjunctions with both sides >= 2 words
     * 4. Weak-word avoidance + best character balance
     * 5. Midpoint fallback
     */
    fun findBestSplit(words: List<String>, maxCharsPerLine: Int = MAX_CHARS_PER_LINE): Int {
        val totalWords = words.size
        if (totalWords <= 2) return 1

        // 1. Sentence-ending punctuation
        for (i in 1 until totalWords - 1) {
            val lastChar = words[i - 1].lastOrNull()
            if (lastChar == '.' || lastChar == '?' || lastChar == '!') {
                if (totalWords - i >= 3) return i
            }
        }

        // 2. Comma / Semicolon
        for (i in 1 until totalWords - 1) {
            val lastChar = words[i - 1].lastOrNull()
            if (lastChar == ',' || lastChar == ';') {
                if (totalWords - i >= 3) return i
            }
        }

        // 3. Conjunctions
        for (i in 1 until totalWords - 1) {
            val w = words[i].lowercase(Locale.ROOT)
            if (w in CONJUNCTIONS && i >= 2 && totalWords - i >= 3) {
                return i
            }
        }

        // 4. Weak-word avoidance + character balance
        var bestSplit: Int? = null
        var bestBalance = Double.MAX_VALUE
        for (i in 2 until totalWords - 2) {
            val prevWord = words[i - 1].lowercase(Locale.ROOT).trim('.', ',', ';', '!', '?')
            val nextWord = words[i].lowercase(Locale.ROOT).trim('.', ',', ';', '!', '?')
            if (prevWord !in WEAK_WORDS && nextWord !in WEAK_WORDS) {
                val len1 = words.subList(0, i).joinToString(" ").length
                val len2 = words.subList(i, totalWords).joinToString(" ").length
                val balance = kotlin.math.abs(len1 - len2).toDouble()
                if (balance < bestBalance) {
                    bestBalance = balance
                    bestSplit = i
                }
            }
        }
        if (bestSplit != null) return bestSplit

        // 5. Midpoint fallback
        return totalWords / 2
    }

    /**
     * Groups word-level timestamps into balanced 1- or 2-line subtitle blocks.
     */
    fun createBalancedSrtBlocks(
        words: List<WordInfo>,
        maxCharsPerLine: Int = MAX_CHARS_PER_LINE,
        maxSilenceGap: Double = MAX_SILENCE_GAP
    ): List<SubtitleBlock> {
        val blocks = mutableListOf<SubtitleBlock>()
        var currentWords = mutableListOf<String>()
        var currentLen = 0
        var startTime: Double? = null
        var lastWordEnd = 0.0

        for (wordInfo in words) {
            val rawWord = wordInfo.word.trim()
            if (rawWord.isEmpty()) continue

            // Silence gap: flush current block
            if (lastWordEnd > 0.0 && (wordInfo.start - lastWordEnd) > maxSilenceGap) {
                if (currentWords.isNotEmpty() && startTime != null) {
                    blocks.add(SubtitleBlock(startTime, lastWordEnd, currentWords.joinToString(" ")))
                    currentWords = mutableListOf()
                    currentLen = 0
                    startTime = null
                }
            }

            if (startTime == null) {
                startTime = wordInfo.start
            }

            // Oversized word handling
            if (rawWord.length > maxCharsPerLine) {
                val chunks = splitOversizedWord(rawWord, wordInfo.start, wordInfo.end, maxCharsPerLine)
                if (currentWords.isNotEmpty()) {
                    blocks.add(SubtitleBlock(startTime, lastWordEnd, currentWords.joinToString(" ")))
                    currentWords = mutableListOf()
                    currentLen = 0
                }

                for (idx in 0 until chunks.size - 1) {
                    val chunk = chunks[idx]
                    blocks.add(SubtitleBlock(chunk.start, chunk.end, chunk.word))
                }

                val lastChunk = chunks.last()
                startTime = lastChunk.start
                currentWords = mutableListOf(lastChunk.word)
                currentLen = lastChunk.word.length
                lastWordEnd = lastChunk.end
                continue
            }

            val wordLen = rawWord.length
            if (currentWords.isNotEmpty() && (currentLen + wordLen + 1 > maxCharsPerLine)) {
                val splitIdx = findBestSplit(currentWords, maxCharsPerLine)
                val firstLine = currentWords.subList(0, splitIdx).joinToString(" ")
                var secondLine = currentWords.subList(splitIdx, currentWords.size).joinToString(" ")

                val firstWordSecond = secondLine.split(" ").firstOrNull()?.lowercase(Locale.ROOT) ?: ""
                if (firstWordSecond.isNotEmpty() && firstWordSecond !in CONJUNCTIONS) {
                    secondLine = secondLine.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                }

                val finalText = if (secondLine.isNotEmpty()) "$firstLine\n$secondLine" else firstLine
                blocks.add(SubtitleBlock(startTime, lastWordEnd, finalText))

                currentWords = mutableListOf(rawWord)
                currentLen = wordLen
                startTime = wordInfo.start
                lastWordEnd = wordInfo.end
            } else {
                currentWords.add(rawWord)
                currentLen += wordLen + (if (currentWords.size > 1) 1 else 0)
                lastWordEnd = wordInfo.end

                // Flush cleanly at sentence boundaries so dialogue cues stay locked to phrasing
                val endsSentence = rawWord.endsWith('.') || rawWord.endsWith('?') || rawWord.endsWith('!')
                if (endsSentence && currentLen >= 14 && startTime != null) {
                    val textOut = if (currentLen > maxCharsPerLine && currentWords.size > 2) {
                        val splitIdx = findBestSplit(currentWords, maxCharsPerLine)
                        val l1 = currentWords.subList(0, splitIdx).joinToString(" ")
                        val l2 = currentWords.subList(splitIdx, currentWords.size).joinToString(" ")
                        if (l2.isNotEmpty()) "$l1\n$l2" else l1
                    } else {
                        currentWords.joinToString(" ")
                    }
                    blocks.add(SubtitleBlock(startTime, lastWordEnd, textOut))
                    currentWords = mutableListOf()
                    currentLen = 0
                    startTime = null
                }
            }
        }

        if (currentWords.isNotEmpty() && startTime != null) {
            blocks.add(SubtitleBlock(startTime, lastWordEnd, currentWords.joinToString(" ")))
        }

        return blocks
    }

    /**
     * Applies offset, CPS-based extension, end padding, and min/max duration clamping while keeping
     * every block's start time anchored strictly to actual speech onset (trimming previous tails
     * instead of cascading subsequent dialogue downstream).
     */
    fun applyTimingRules(
        blocks: List<SubtitleBlock>,
        offset: Double = SUBTITLE_OFFSET,
        endPadding: Double = END_PADDING
    ): List<SubtitleBlock> {
        if (blocks.isEmpty()) return emptyList()

        val adjusted = mutableListOf<DoubleArray>() // [start, end]
        val texts = mutableListOf<String>()

        for (b in blocks) {
            val s = (b.start + offset).coerceAtLeast(0.0)
            var e = b.end + offset
            var rawDur = e - s
            if (rawDur <= 0.0) {
                rawDur = 0.25
                e = s + rawDur
            }

            // Visible character count (ignoring line breaks)
            val visibleText = b.text.replace("\n", " ").trim()
            val charCount = visibleText.length
            val cps = if (rawDur > 0.0) charCount / rawDur else 0.0

            if (cps > MAX_CPS) {
                val idealDuration = charCount / MAX_CPS
                var extension = idealDuration - rawDur
                if (extension > CPS_EXTENSION_LIMIT) {
                    extension = CPS_EXTENSION_LIMIT
                }
                e += extension
            }

            e += endPadding
            val dur = e - s
            if (dur < MIN_DURATION) {
                e = s + MIN_DURATION
            }
            if (dur > MAX_DURATION) {
                e = s + MAX_DURATION // hard ceiling wins
            }

            adjusted.add(doubleArrayOf(s, e))
            texts.add(b.text)
        }

        // Enforce frame gap by trimming previous block tail rather than pushing currStart forward
        for (i in 1 until adjusted.size) {
            val prevStart = adjusted[i - 1][0]
            var prevEnd = adjusted[i - 1][1]
            var currStart = adjusted[i][0]

            if (currStart < prevStart + FRAME_GAP + 0.12) {
                currStart = prevStart + FRAME_GAP + 0.12
                adjusted[i][0] = currStart
                if (adjusted[i][1] < currStart + 0.20) {
                    adjusted[i][1] = currStart + 0.20
                }
            }

            val maxPrevEnd = currStart - FRAME_GAP
            if (prevEnd > maxPrevEnd) {
                adjusted[i - 1][1] = maxPrevEnd.coerceAtLeast(prevStart + 0.12)
            }
        }

        for (i in adjusted.indices) {
            if (adjusted[i][1] <= adjusted[i][0]) {
                adjusted[i][1] = adjusted[i][0] + 0.25
            }
            if (adjusted[i][1] - adjusted[i][0] > MAX_DURATION) {
                adjusted[i][1] = adjusted[i][0] + MAX_DURATION
            }
        }

        return adjusted.mapIndexed { idx, pair ->
            SubtitleBlock(pair[0], pair[1], texts[idx])
        }
    }

    fun formatTimestampSrt(seconds: Double): String {
        val totalMs = (seconds * 1000).toLong().coerceAtLeast(0)
        val hours = totalMs / 3600000
        val mins = (totalMs % 3600000) / 60000
        val secs = (totalMs % 60000) / 1000
        val ms = totalMs % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, mins, secs, ms)
    }

    fun formatSrt(blocks: List<SubtitleBlock>): String {
        val sb = StringBuilder()
        blocks.forEachIndexed { index, block ->
            sb.append("${index + 1}\n")
            sb.append("${formatTimestampSrt(block.start)} --> ${formatTimestampSrt(block.end)}\n")
            sb.append("${block.text}\n\n")
        }
        return sb.toString().trimEnd() + "\n"
    }

    /**
     * Converts SubtitleBlocks directly to PlaybackRules.SubtitleCue for instant in-memory playback.
     */
    fun toSubtitleCues(blocks: List<SubtitleBlock>): List<PlaybackRules.SubtitleCue> {
        return blocks.map {
            PlaybackRules.SubtitleCue(
                startMs = (it.start * 1000).toLong(),
                endMs = (it.end * 1000).toLong(),
                text = it.text
            )
        }
    }
}

