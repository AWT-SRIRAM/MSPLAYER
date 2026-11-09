package app.msplayer.offline

import org.junit.Assert.*
import org.junit.Test

class SpeechToTextClientTest {

    @Test
    fun testParseVerboseJsonWords() {
        val sampleJson = """
            {
              "task": "transcribe",
              "language": "english",
              "duration": 5.2,
              "text": "Hello world welcome to MS PLAYER",
              "words": [
                {"word": "Hello", "start": 0.0, "end": 0.5},
                {"word": "world", "start": 0.5, "end": 1.0},
                {"word": "welcome", "start": 1.2, "end": 1.8},
                {"word": "to", "start": 1.8, "end": 2.0},
                {"word": "MS", "start": 2.0, "end": 2.3},
                {"word": "PLAYER", "start": 2.3, "end": 3.0}
              ]
            }
        """.trimIndent()

        val words = SpeechToTextClient.parseVerboseJson(sampleJson)
        assertEquals(6, words.size)
        assertEquals("Hello", words[0].word)
        assertEquals(0.0, words[0].start, 0.001)
        assertEquals(0.5, words[0].end, 0.001)
        assertEquals("PLAYER", words.last().word)

        val cues = SpeechToTextClient.buildSubtitleCues(words)
        assertTrue(cues.isNotEmpty())
        assertEquals("Hello world welcome to MS PLAYER", cues[0].text)
        assertTrue(cues[0].endMs > cues[0].startMs)
    }

    @Test
    fun testParseVerboseJsonSegmentsFallback() {
        val sampleJson = """
            {
              "text": "Fallback line",
              "segments": [
                {
                  "id": 0,
                  "start": 0.0,
                  "end": 2.0,
                  "text": "Fallback line"
                }
              ]
            }
        """.trimIndent()

        val words = SpeechToTextClient.parseVerboseJson(sampleJson)
        assertEquals(2, words.size)
        assertEquals("Fallback", words[0].word)
        assertEquals("line", words[1].word)
    }

    @Test
    fun testFormatSrtTimeAndToSrt() {
        val t1 = SpeechToTextClient.formatSrtTime(0)
        assertEquals("00:00:00,000", t1)

        val t2 = SpeechToTextClient.formatSrtTime(3661500) // 1h 1m 1s 500ms
        assertEquals("01:01:01,500", t2)

        val cues = listOf(
            PlaybackRules.SubtitleCue(1000, 3000, "Line 1"),
            PlaybackRules.SubtitleCue(3500, 5000, "Line 2")
        )
        val srt = SpeechToTextClient.toSrt(cues)
        assertTrue(srt.contains("1\r\n00:00:01,000 --> 00:00:03,000\r\nLine 1"))
        assertTrue(srt.contains("2\r\n00:00:03,500 --> 00:00:05,000\r\nLine 2"))
    }

    @Test
    fun testParseVerboseJsonPrefersDeepgramPunctuatedWordAndUnescapes() {
        val deepgramJson = """
            {
              "results": {
                "channels": [{
                  "alternatives": [{
                    "words": [
                      {"word": "hi", "start": 0.1, "end": 0.4, "confidence": 0.99, "punctuated_word": "\"Hi,\""},
                      {"word": "komi", "start": 0.5, "end": 0.9, "confidence": 0.98, "punctuated_word": "Komi!"}
                    ]
                  }]
                }]
              }
            }
        """.trimIndent()

        val words = SpeechToTextClient.parseVerboseJson(deepgramJson)
        assertEquals(2, words.size)
        assertEquals("\"Hi,\"", words[0].word)
        assertEquals("Komi!", words[1].word)
    }

    @Test
    fun testRfc2046BoundaryIsPureAlphanumeric() {
        val boundary = SpeechToTextClient.generateMultipartBoundary(123456789L)
        assertTrue(boundary.matches(Regex("^[A-Za-z0-9]+$")))
        assertFalse(boundary.contains("="))
    }

    @Test
    fun testParseApiKeysMultiSeparatorAndDeduplicates() {
        val keys = SpeechToTextClient.parseApiKeys("keyA, keyB;keyC\nkeyA , ")
        assertEquals(listOf("keyA", "keyB", "keyC"), keys)
    }

    @Test
    fun testEnglishLanguagePreferenceAndChunkLimit() {
        assertTrue(SpeechToTextClient.isEnglishLanguageTag("en"))
        assertTrue(SpeechToTextClient.isEnglishLanguageTag("eng"))
        assertTrue(SpeechToTextClient.isEnglishLanguageTag("en-US"))
        assertTrue(SpeechToTextClient.isEnglishLanguageTag("English"))
        assertFalse(SpeechToTextClient.isEnglishLanguageTag("jpn"))
        assertFalse(SpeechToTextClient.isEnglishLanguageTag("und"))
        assertTrue(SpeechToTextClient.MAX_GROQ_CHUNK_BYTES <= 12L * 1024L * 1024L)
    }

    @Test
    fun testDirectMuxSplitEligibilityAndPipelinedChunkDuration() {
        // Compact AAC/Opus under threshold qualifies for zero-reencode direct split
        assertTrue(SpeechToTextClient.shouldDirectMuxSplit("audio/mp4a-latm", true, 128_000L, 40L * 1024L * 1024L))
        assertTrue(SpeechToTextClient.shouldDirectMuxSplit("audio/opus", true, 96_000L, 80L * 1024L * 1024L))
        // Unsupported direct mux or heavy codecs must fall back to fast speech transcode
        assertFalse(SpeechToTextClient.shouldDirectMuxSplit("audio/mp4a-latm", false, 128_000L, 10L * 1024L * 1024L))
        assertFalse(SpeechToTextClient.shouldDirectMuxSplit("audio/ac3", true, 192_000L, 10L * 1024L * 1024L))
        assertFalse(SpeechToTextClient.shouldDirectMuxSplit("audio/flac", true, 192_000L, 10L * 1024L * 1024L))
        // High-bitrate compact codecs must transcode so speech stays tiny and uploads fast
        assertFalse(SpeechToTextClient.shouldDirectMuxSplit("audio/mp4a-latm", true, 320_000L, 50L * 1024L * 1024L))
        // Unknown bitrate only uses direct split on small sources
        assertTrue(SpeechToTextClient.shouldDirectMuxSplit("audio/mp4a-latm", true, -1L, 8L * 1024L * 1024L))
        assertFalse(SpeechToTextClient.shouldDirectMuxSplit("audio/mp4a-latm", true, -1L, 25L * 1024L * 1024L))

        // Chunk 0 uses shorter 4-min slice on longer media for earlier live subtitle preview
        val longDurationUs = 25L * 60L * 1_000_000L
        assertEquals(SpeechToTextClient.FIRST_CHUNK_DURATION_US, SpeechToTextClient.chunkLimitDurationUs(0, longDurationUs))
        assertEquals(SpeechToTextClient.MAX_CHUNK_DURATION_US, SpeechToTextClient.chunkLimitDurationUs(1, longDurationUs))
        assertEquals(SpeechToTextClient.MAX_CHUNK_DURATION_US, SpeechToTextClient.chunkLimitDurationUs(0, 5L * 60L * 1_000_000L))
    }

    @Test
    fun testSanitizeErrorMessageHidesServiceBrandAndClarifiesNetwork() {
        assertEquals(
            "Unable to reach AI Subtitles service — check your internet connection",
            SpeechToTextClient.sanitizeErrorMessage("""Unable to resolve host "api.deepgram.com": No address associated with hostname"""),
        )
        assertEquals(
            "Unable to reach AI Subtitles service — check your internet connection",
            SpeechToTextClient.sanitizeErrorMessage("""Unable to resolve host "api.groq.com": No address associated with hostname"""),
        )
        val cleaned = SpeechToTextClient.sanitizeErrorMessage("Groq Cloud HTTP 429: rate limit")
        assertFalse(cleaned.contains("groq", ignoreCase = true))
        assertFalse(cleaned.contains("deepgram", ignoreCase = true))
        assertTrue(cleaned.contains("AI Subtitles"))
    }
}

