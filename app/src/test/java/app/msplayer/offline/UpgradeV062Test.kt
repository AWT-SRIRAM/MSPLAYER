package app.msplayer.offline

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class UpgradeV062Test {

    @Test
    fun speedStopsExcludeIntermediateTemporaryStops() {
        val expected = floatArrayOf(0.25f, 0.5f, 1f, 1.5f, 2f, 2.5f, 3f, 4f)
        assertArrayEquals(expected, GestureRules.rates, 0.0001f)
        assertArrayEquals(expected, GestureRules.rulerStops, 0.0001f)
        listOf(0.75f, 1.25f, 1.75f, 3.5f).forEach { removed ->
            assertFalse(GestureRules.rates.any { kotlin.math.abs(it - removed) < 0.001f })
        }
    }

    @Test
    fun parseMediaHintsExtractsTitleYearSeasonEpisode() {
        val show = OnlineSubtitlesClient.parseMediaHints("Severance.S02E06.1080p.WEB-DL.DDP5.1.H264.mkv")
        assertEquals("Severance", show.title)
        assertEquals(2, show.season)
        assertEquals(6, show.episode)
        assertTrue(show.isSeries)

        val movie = OnlineSubtitlesClient.parseMediaHints("Inception (2010) 1080p BluRay x265.mp4")
        assertEquals("Inception", movie.title)
        assertEquals(2010, movie.year)
        assertNull(movie.season)
        assertNull(movie.episode)
        assertFalse(movie.isSeries)

        val nxn = OnlineSubtitlesClient.parseMediaHints("Dark.1x03.720p.NF.WEB.mkv")
        assertEquals("Dark", nxn.title)
        assertEquals(1, nxn.season)
        assertEquals(3, nxn.episode)
        assertTrue(nxn.isSeries)
    }

    @Test
    fun tmdbAndOnlineProviderParsersAndRankerWorkCleanly() {
        val tmdbSearchJson = """
            {
              "results": [
                {"id": 100, "media_type": "movie", "title": "Severance", "release_date": "2006-08-25", "popularity": 18.2},
                {"id": 95396, "media_type": "tv", "name": "Severance", "first_air_date": "2022-02-18", "popularity": 94.0}
              ]
            }
        """.trimIndent()
        val seriesHints = OnlineSubtitlesClient.MediaHints(title = "Severance", season = 2, episode = 6, isSeries = true)
        val tmdbMatch = OnlineSubtitlesClient.parseTmdbSearch(tmdbSearchJson, seriesHints)
        assertNotNull(tmdbMatch)
        assertEquals(95396, tmdbMatch!!.tmdbId)
        assertEquals("tv", tmdbMatch.mediaType)

        assertEquals("tt11280740", OnlineSubtitlesClient.parseTmdbExternalIds("""{"id":95396,"imdb_id":"tt11280740"}"""))

        val wyzieJson = """
            [
              {"id":"w1","url":"https://sub.wyzie.io/c/1.srt","format":"srt","language":"en","release":"Severance.S02E06.1080p.WEB","isHearingImpaired":false,"source":"wyzie"},
              {"id":"w2","url":"https://sub.wyzie.io/c/2","format":"srt","language":"ta","release":"Mr.Robot.S01E01","isHearingImpaired":true}
            ]
        """.trimIndent()
        val wyzieList = OnlineSubtitlesClient.parseWyzieResponse(wyzieJson)
        assertEquals(2, wyzieList.size)
        assertEquals("Severance.S02E06.1080p.WEB.srt", wyzieList[0].fileName)
        assertEquals("Mr.Robot.S01E01.srt", wyzieList[1].fileName)

        val subDlJson = """
            {
              "status": true,
              "subtitles": [
                {"release_name":"Severance.S02E06.WEB-DL","name":"Severance.S02E06","url":"/subtitle/3344-5566.zip","lang":"english","downloads":420,"hi":false}
              ]
            }
        """.trimIndent()
        val subDlList = OnlineSubtitlesClient.parseSubDlResponse(subDlJson)
        assertEquals(1, subDlList.size)
        assertEquals("https://dl.subdl.com/subtitle/3344-5566.zip", subDlList[0].downloadUrl)
        assertEquals("ENGLISH", subDlList[0].language)

        val subSrcMovies = """
            {"data":[{"movieId":777,"title":"Severance","releaseYear":2022,"type":"tv"}]}
        """.trimIndent()
        assertEquals(777, OnlineSubtitlesClient.parseSubSourceFirstMovieId(subSrcMovies, seriesHints))

        val subSrcListJson = """
            {"data":[{"subtitleId":999,"language":"english","releaseInfo":["Severance.S02E05.WEB"],"downloadUrl":"https://api.subsource.net/v1/dl/999"}]}
        """.trimIndent()
        val subSrcList = OnlineSubtitlesClient.parseSubSourceSubtitles(subSrcListJson, "en")
        assertEquals(1, subSrcList.size)
        assertEquals("subsource:999", subSrcList[0].id)

        val ranked = OnlineSubtitlesClient.rankAndDeduplicate(
            candidates = wyzieList + subDlList + subSrcList,
            hints = seriesHints,
            rawVideoName = "Severance.S02E06.1080p.WEB-DL.mkv",
        )
        assertTrue(ranked.size == 4)
        // S02E06 candidates should rank ahead of S02E05 & Mr.Robot
        assertTrue(ranked.first().release.contains("S02E06"))
    }

    @Test
    fun subtitleTranslatorStripsAssHtmlAndPreservesIds() {
        val raw = "\uFEFF{\\i1\\pos(100,200)}<i>Hello\\Nworld!</i>\r"
        assertEquals("Hello\nworld!", SubtitleTranslator.cleanCueText(raw))

        val fenced = """
            ```json
            [
              {"id": 0, "t": "வணக்கம்"},
              {"id": 1, "t": "எப்படி இருக்கிறீர்கள்?"},
              {"id": 99, "t": "Should be ignored"}
            ]
            ```
        """.trimIndent()
        val parsed = SubtitleTranslator.parseTranslatedBatch(fenced, setOf(0, 1))
        assertEquals(2, parsed.size)
        assertEquals("வணக்கம்", parsed[0])
        assertEquals("எப்படி இருக்கிறீர்கள்?", parsed[1])
        assertNull(parsed[99])

        val wrapped = SubtitleTranslator.balanceTranslatedLine(
            "This is a deliberately longer translated movie subtitle sentence that needs splitting",
            maxCharsPerLine = 38,
        )
        assertTrue(wrapped.contains('\n'))
    }

    @Test
    fun audioEffectsFiveBandReverbAndSpatializerWork() {
        val base = AudioEffectSettings(preset = "Rock", bassBoost = 50, virtualizer = 40, reverb = "Medium Hall")
        assertEquals(5, base.fiveBandGains().size)
        assertTrue(base.spatializerActive())
        val opts = base.spatializerOptions()
        assertTrue(opts.contains("--audio-filter=spatializer"))
        assertTrue(opts.any { it.startsWith("--spatializer-width=") })

        val edited = base.withFiveBandGain(2, 6.0f)
        assertEquals("Custom", edited.preset)
        assertEquals(6.0f, edited.gains()[4], 0.01f)
        assertEquals(6.0f, edited.gains()[5], 0.01f)
        assertTrue(AudioEffectSettings.reverbPresets.contains("Plate"))
        assertEquals(AudioEffectSettings(preset = "Flat").gains().all { it == 0f }, true)
    }

    @Test
    fun speechDecimationScientificNotationAndBinarySearchCuesWork() {
        assertEquals(6, SpeechToTextClient.decimationFactorForSampleRate(96000))
        assertEquals(6, SpeechToTextClient.decimationFactorForSampleRate(88200))
        assertEquals(3, SpeechToTextClient.decimationFactorForSampleRate(48000))
        assertEquals(1, SpeechToTextClient.decimationFactorForSampleRate(16000))

        val sciJson = """
            {
              "words": [
                {"word": "First", "start": -5e-2, "end": 5.0e-1},
                {"word": "Second", "start": 1.2e0, "end": 1.85e0}
              ]
            }
        """.trimIndent()
        val words = SpeechToTextClient.parseVerboseJson(sciJson)
        assertEquals(2, words.size)
        assertEquals(0.0, words[0].start, 0.001)
        assertEquals(0.5, words[0].end, 0.001)
        assertEquals(1.2, words[1].start, 0.001)

        val cues = listOf(
            PlaybackRules.SubtitleCue(1000L, 6000L, "Background sign"),
            PlaybackRules.SubtitleCue(2500L, 4500L, "Dialogue line"),
            PlaybackRules.SubtitleCue(8000L, 9500L, "Later line"),
        )
        val activeAt3s = PlaybackRules.findActiveCueLines(cues, 3000L)
        assertEquals(listOf("Background sign", "Dialogue line"), activeAt3s)
        assertEquals(listOf("Later line"), PlaybackRules.findActiveCueLines(cues, 8500L))
        assertTrue(PlaybackRules.findActiveCueLines(cues, 7000L).isEmpty())
    }

    @Test
    fun extractSubtitleHandlesRawSrtNamedAsZipOrDottedTitle() {
        val dir = Files.createTempDirectory("msplayer_sub_test").toFile()
        try {
            val rawSrt = "1\r\n00:00:01,000 --> 00:00:03,000\r\nHello Test\r\n".toByteArray()
            val files = OpenSubtitlesClient.extractSubtitleFromStream(
                input = ByteArrayInputStream(rawSrt),
                originalName = "Mr.Robot.S01E01.zip",
                destDir = dir,
                preferredPrefix = "Mr.Robot",
            )
            assertEquals(1, files.size)
            assertEquals("srt", files[0].extension)
            assertTrue(files[0].readText().contains("Hello Test"))
        } finally {
            dir.deleteRecursively()
        }
    }
}

