package app.msplayer.offline

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater

class CrystalUpgradeTest {

    @Test
    fun parseSubtitleCuesSupportsHourlessWebVttAndSrt() {
        val vtt = """
            WEBVTT

            00:05.250 --> 00:08.500
            Opening line

            01:02:03.100 --> 01:02:06.900
            Hour timestamp line

            3
            00:00:12,000 --> 00:00:14,250
            Comma SRT line
        """.trimIndent()

        val cues = PlaybackRules.parseSubtitleCues(vtt)
        assertEquals(3, cues.size)
        assertEquals(5250L, cues[0].startMs)
        assertEquals(8500L, cues[0].endMs)
        assertEquals("Opening line", cues[0].text)

        assertEquals(12000L, cues[1].startMs)
        assertEquals(14250L, cues[1].endMs)
        assertEquals("Comma SRT line", cues[1].text)

        val hourMs = 3600000L + 2 * 60000L + 3100L
        assertEquals(hourMs, cues[2].startMs)
        assertEquals("Hour timestamp line", cues[2].text)
    }

    @Test
    fun choosePrimarySubtitleHonorsStarredTracksAndFavoriteLanguages() {
        val tracks = listOf(
            -1 to "Disable",
            1 to "Signs & Songs [eng]",
            2 to "Track 1 [eng]",
            3 to "Track 2 [jpn]",
            4 to "Track 3 [tam]",
        )

        assertEquals(
            4 to "Track 3 [tam]",
            PlaybackRules.choosePrimarySubtitle(
                tracks = tracks,
                preferredName = null,
                favoriteLanguages = listOf("ta", "ja"),
                starredTracks = emptySet(),
            ),
        )

        assertEquals(
            3 to "Track 2 [jpn]",
            PlaybackRules.choosePrimarySubtitle(
                tracks = tracks,
                preferredName = null,
                favoriteLanguages = listOf("ta", "ja"),
                starredTracks = setOf("track 2 [jpn]"),
            ),
        )

        // Exact preferredName still wins over stars/favorites when present
        assertEquals(
            2 to "Track 1 [eng]",
            PlaybackRules.choosePrimarySubtitle(
                tracks = tracks,
                preferredName = "Track 1 [eng]",
                favoriteLanguages = listOf("ta"),
                starredTracks = setOf("track 3 [tam]"),
            ),
        )
    }

    @Test
    fun translatorCatalogLookupMatchingAndPayloadsWorkForAllLanguages() {
        assertTrue(SubtitleTranslator.supportedLanguages.size >= 36)
        val japanese = SubtitleTranslator.findLanguage("jpn")
        assertNotNull(japanese)
        assertEquals("ja", japanese?.code)
        assertEquals("Japanese", japanese?.label)

        val hindi = SubtitleTranslator.findLanguage("हिन्दी")
        assertNotNull(hindi)
        assertEquals("hi", hindi?.code)

        assertTrue(SubtitleTranslator.matchesLanguage("Tamil [tam]", "ta"))
        assertTrue(SubtitleTranslator.matchesLanguage("track_jp.srt", "ja"))
        assertFalse(SubtitleTranslator.matchesLanguage("French [fre]", "ta"))

        val batch = listOf(
            IndexedValue(0, PlaybackRules.SubtitleCue(1000L, 2500L, "Let's go!")),
        )
        val payload = SubtitleTranslator.buildChatPayload("llama-3.3-70b-versatile", batch, japanese!!)
        assertTrue(payload.contains("Japanese"))
        assertTrue(payload.contains("\\\"id\\\":0"))
        val parsedPayload = OpenSubtitlesClient.JsonLite.parseObject(payload)
        val messages = parsedPayload["messages"] as? List<*>
        assertEquals(2, messages?.size)
        val userContent = (messages?.get(1) as? Map<*, *>)?.get("content")?.toString().orEmpty()
        assertTrue(userContent.contains("\"id\":0"))
        assertTrue(userContent.contains("Let's go!"))

        assertEquals("", OnlineSubtitlesClient.toSubDlLanguageCode("all"))
        assertEquals("JA", OnlineSubtitlesClient.toSubDlLanguageCode("ja"))
        assertEquals("ZH", OnlineSubtitlesClient.toSubDlLanguageCode("zh-cn"))
        assertEquals("japanese", OnlineSubtitlesClient.toSubSourceLanguageSlug("ja"))
        assertEquals("jpn", OpenSubtitlesClient.filterForLanguage("ja").code3)
    }

    @Test
    fun containerDemuxerParsesMkvEbmlChapters() {
        fun vint(len: Int): ByteArray = byteArrayOf((0x80 or len).toByte())
        fun elem(id: ByteArray, body: ByteArray): ByteArray = id + vint(body.size) + body
        fun str(id: ByteArray, text: String) = elem(id, text.toByteArray(Charsets.UTF_8))
        fun u64(id: ByteArray, value: Long): ByteArray {
            val bytes = ByteArray(8)
            for (i in 0..7) {
                bytes[7 - i] = ((value ushr (i * 8)) and 0xFFL).toByte()
            }
            return elem(id, bytes)
        }

        val ch1 = elem(
            byteArrayOf(0xB6.toByte()),
            u64(byteArrayOf(0x91.toByte()), 0L) +
                elem(byteArrayOf(0x80.toByte()), str(byteArrayOf(0x85.toByte()), "Prologue")),
        )
        val ch2 = elem(
            byteArrayOf(0xB6.toByte()),
            u64(byteArrayOf(0x91.toByte()), 125_000_000_000L) +
                elem(byteArrayOf(0x80.toByte()), str(byteArrayOf(0x85.toByte()), "Opening Theme")),
        )
        val ch3Unnamed = elem(
            byteArrayOf(0xB6.toByte()),
            u64(byteArrayOf(0x91.toByte()), 300_000_000_000L),
        )
        val edition = elem(byteArrayOf(0x45, 0xB9.toByte()), ch1 + ch2 + ch3Unnamed)
        val chaptersEl = elem(byteArrayOf(0x10, 0x43, 0xA7.toByte(), 0x70), edition)
        val ebmlHeader = elem(
            byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()),
            str(byteArrayOf(0x42, 0x82.toByte()), "matroska"),
        )
        val segmentHeader = elem(byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67), chaptersEl)

        val tmp = File.createTempFile("demux_chapters_test_", ".mkv")
        try {
            tmp.writeBytes(ebmlHeader + segmentHeader)
            val result = ContainerDemuxer.parseMkv(tmp)
            assertEquals(3, result.chapters.size)
            assertEquals(0L, result.chapters[0].startMs)
            assertEquals("Prologue", result.chapters[0].title)
            assertEquals(125_000L, result.chapters[1].startMs)
            assertEquals("Opening Theme", result.chapters[1].title)
            assertEquals(300_000L, result.chapters[2].startMs)
            assertEquals("Chapter 3", result.chapters[2].title)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun subtitleStyleSecondaryDefaultsAndQuickPresetsWork() {
        val style = SubtitleStyle()
        assertEquals(0x88CCFF, style.secondaryColor)
        assertEquals(90, style.secondaryScale)
        assertTrue(SubtitleStyle.quickPresets.size >= 4)
        assertTrue(SubtitleStyle.quickPresets.all { it.builtIn })
        assertTrue(SubtitleStyle.quickPresets.any { it.name == "Cinema" })
        assertTrue(SubtitleStyle.quickPresets.any { it.name == "Anime" })
    }

    @Test
    fun choosePrimaryAudioHonorsFavoritesAndDeprioritizesCommentary() {
        val tracks = listOf(
            -1 to "Disable",
            1 to "English 5.1 [eng]",
            2 to "Tamil Director Commentary [tam]",
            3 to "Tamil Atmos [tam]",
            4 to "Japanese Stereo [jpn]",
        )

        assertEquals(
            3 to "Tamil Atmos [tam]",
            PlaybackRules.choosePrimaryAudio(
                tracks = tracks,
                preferredName = null,
                favoriteLanguages = listOf("ta", "en"),
            ),
        )

        assertEquals(
            1 to "English 5.1 [eng]",
            PlaybackRules.choosePrimaryAudio(
                tracks = tracks,
                preferredName = "English 5.1 [eng]",
                favoriteLanguages = listOf("ta", "en"),
            ),
        )
    }

    @Test
    fun resolveTapSeekRequiresDoubleTapPerStepAndTogglesPlayPauseOnCenter() {
        val slop = 50f
        assertEquals(PlaybackRules.TapZone.LEFT, PlaybackRules.tapZoneForFraction(0.20f))
        assertEquals(PlaybackRules.TapZone.CENTER, PlaybackRules.tapZoneForFraction(0.50f))
        assertEquals(PlaybackRules.TapZone.RIGHT, PlaybackRules.tapZoneForFraction(0.80f))

        // First tap never seeks or pauses
        val first = PlaybackRules.resolveTapSeek(
            nowMs = 1000L,
            distanceFromLastTapPx = 0f,
            lastTapMs = 0L,
            doubleTapMaxSlopPx = slop,
            stepSec = 10,
            currentPositionMs = 50_000L,
            durationMs = 300_000L,
            currentBurst = PlaybackRules.TapSeekBurst(),
            zone = PlaybackRules.TapZone.RIGHT,
            lastZone = null,
        )
        assertFalse(first.didSeek)
        assertEquals(PlaybackRules.TapAction.NONE, first.action)

        // Second tap on RIGHT starts +10s
        val doubleTapRight = PlaybackRules.resolveTapSeek(
            nowMs = 1180L,
            distanceFromLastTapPx = 10f,
            lastTapMs = 1000L,
            doubleTapMaxSlopPx = slop,
            stepSec = 10,
            currentPositionMs = 50_000L,
            durationMs = 300_000L,
            currentBurst = first.nextBurst,
            zone = PlaybackRules.TapZone.RIGHT,
            lastZone = PlaybackRules.TapZone.RIGHT,
        )
        assertTrue(doubleTapRight.didSeek)
        assertEquals(PlaybackRules.TapAction.SEEK_FORWARD, doubleTapRight.action)
        assertEquals(60_000L, doubleTapRight.destinationMs)
        assertEquals(10, doubleTapRight.totalDeltaSec)
        assertEquals(1, doubleTapRight.tapCount)

        // Single tap immediately after double-tap does NOT accidentally seek (lastTap reset = 0L)
        val singleAfterSeek = PlaybackRules.resolveTapSeek(
            nowMs = 1480L,
            distanceFromLastTapPx = 15f,
            lastTapMs = 0L,
            doubleTapMaxSlopPx = slop,
            stepSec = 10,
            currentPositionMs = 60_000L,
            durationMs = 300_000L,
            currentBurst = doubleTapRight.nextBurst,
            zone = PlaybackRules.TapZone.RIGHT,
            lastZone = null,
        )
        assertFalse(singleAfterSeek.didSeek)
        assertEquals(PlaybackRules.TapAction.NONE, singleAfterSeek.action)

        // Completing a second double-tap on RIGHT inside burst window accumulates to +20s
        val secondDoubleTapRight = PlaybackRules.resolveTapSeek(
            nowMs = 1660L,
            distanceFromLastTapPx = 12f,
            lastTapMs = 1480L,
            doubleTapMaxSlopPx = slop,
            stepSec = 10,
            currentPositionMs = 60_000L,
            durationMs = 300_000L,
            currentBurst = singleAfterSeek.nextBurst,
            zone = PlaybackRules.TapZone.RIGHT,
            lastZone = PlaybackRules.TapZone.RIGHT,
        )
        assertTrue(secondDoubleTapRight.didSeek)
        assertEquals(PlaybackRules.TapAction.SEEK_FORWARD, secondDoubleTapRight.action)
        assertEquals(70_000L, secondDoubleTapRight.destinationMs)
        assertEquals(20, secondDoubleTapRight.totalDeltaSec)
        assertEquals(2, secondDoubleTapRight.tapCount)

        // Double-tap in CENTER toggles play/pause and clears seek burst
        val centerDoubleTap = PlaybackRules.resolveTapSeek(
            nowMs = 2000L,
            distanceFromLastTapPx = 8f,
            lastTapMs = 1820L,
            doubleTapMaxSlopPx = slop,
            stepSec = 10,
            currentPositionMs = 70_000L,
            durationMs = 300_000L,
            currentBurst = secondDoubleTapRight.nextBurst,
            zone = PlaybackRules.TapZone.CENTER,
            lastZone = PlaybackRules.TapZone.CENTER,
        )
        assertFalse(centerDoubleTap.didSeek)
        assertEquals(PlaybackRules.TapAction.TOGGLE_PLAY_PAUSE, centerDoubleTap.action)

        // Mismatched zones between tap 1 (LEFT) and tap 2 (CENTER) do not pause or seek
        val mismatchedZones = PlaybackRules.resolveTapSeek(
            nowMs = 2200L,
            distanceFromLastTapPx = 20f,
            lastTapMs = 2050L,
            doubleTapMaxSlopPx = slop,
            stepSec = 10,
            currentPositionMs = 70_000L,
            durationMs = 300_000L,
            currentBurst = PlaybackRules.TapSeekBurst(),
            zone = PlaybackRules.TapZone.CENTER,
            lastZone = PlaybackRules.TapZone.LEFT,
        )
        assertFalse(mismatchedZones.didSeek)
        assertEquals(PlaybackRules.TapAction.NONE, mismatchedZones.action)

        // Double-tap on LEFT rewinds by -10s
        val leftDoubleTap = PlaybackRules.resolveTapSeek(
            nowMs = 2600L,
            distanceFromLastTapPx = 10f,
            lastTapMs = 2420L,
            doubleTapMaxSlopPx = slop,
            stepSec = 10,
            currentPositionMs = 70_000L,
            durationMs = 300_000L,
            currentBurst = PlaybackRules.TapSeekBurst(),
            zone = PlaybackRules.TapZone.LEFT,
            lastZone = PlaybackRules.TapZone.LEFT,
        )
        assertTrue(leftDoubleTap.didSeek)
        assertEquals(PlaybackRules.TapAction.SEEK_BACK, leftDoubleTap.action)
        assertEquals(60_000L, leftDoubleTap.destinationMs)
        assertEquals(-10, leftDoubleTap.totalDeltaSec)
    }

    @Test
    fun appThemeModeFromStorageMigratesLegacyGlassFlag() {
        assertEquals(AppThemeMode.LIQUID_GLASS, AppThemeMode.fromStorage(null, legacyGlassEnabled = true))
        assertEquals(AppThemeMode.DARK, AppThemeMode.fromStorage(null, legacyGlassEnabled = false))
        assertEquals(AppThemeMode.LIGHT, AppThemeMode.fromStorage("light", legacyGlassEnabled = true))
        assertEquals(AppThemeMode.DARK, AppThemeMode.fromStorage("dark", legacyGlassEnabled = true))
        assertEquals(AppThemeMode.LIQUID_GLASS, AppThemeMode.fromStorage("liquid_glass", legacyGlassEnabled = false))
    }

    @Test
    fun containerDemuxerExtractsZlibCompressedMkvMultipleSubtitleTracks() {
        fun vint(len: Int): ByteArray = byteArrayOf((0x80 or len).toByte())
        fun elem(id: ByteArray, body: ByteArray): ByteArray = id + vint(body.size) + body
        fun str(id: ByteArray, text: String) = elem(id, text.toByteArray(Charsets.UTF_8))
        fun uint1(id: ByteArray, v: Int) = elem(id, byteArrayOf(v.toByte()))
        fun uint2(id: ByteArray, v: Int) = elem(id, byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte()))
        fun deflateBytes(raw: ByteArray): ByteArray {
            val def = Deflater()
            def.setInput(raw)
            def.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(128)
            while (!def.finished()) {
                val n = def.deflate(buf)
                out.write(buf, 0, n)
            }
            def.end()
            return out.toByteArray()
        }

        // ContentEncoding algo=0 (zlib): 0x6D80 -> 0x6240 -> 0x5034 -> 0x4254 = 0
        val zlibComp = elem(byteArrayOf(0x42, 0x54.toByte()), byteArrayOf(0x00))
        val contentEncoding = elem(byteArrayOf(0x62, 0x40.toByte()), elem(byteArrayOf(0x50, 0x34.toByte()), zlibComp))
        val contentEncodings = elem(byteArrayOf(0x6D, 0x80.toByte()), contentEncoding)

        // Track 2: English UTF8 zlib-compressed
        val track2 = elem(
            byteArrayOf(0xAE.toByte()),
            uint1(byteArrayOf(0xD7.toByte()), 2) +
                uint1(byteArrayOf(0x83.toByte()), 17) +
                str(byteArrayOf(0x86.toByte()), "S_TEXT/UTF8") +
                str(byteArrayOf(0x53, 0x6E.toByte()), "English") +
                str(byteArrayOf(0x22, 0xB5.toByte(), 0x9C.toByte()), "eng") +
                contentEncodings,
        )
        // Track 3: Tamil UTF8 zlib-compressed
        val track3 = elem(
            byteArrayOf(0xAE.toByte()),
            uint1(byteArrayOf(0xD7.toByte()), 3) +
                uint1(byteArrayOf(0x83.toByte()), 17) +
                str(byteArrayOf(0x86.toByte()), "S_TEXT/UTF8") +
                str(byteArrayOf(0x53, 0x6E.toByte()), "Tamil") +
                str(byteArrayOf(0x22, 0xB5.toByte(), 0x9C.toByte()), "tam") +
                contentEncodings,
        )
        val tracksEl = elem(byteArrayOf(0x16, 0x54, 0xAE.toByte(), 0x6B), track2 + track3)

        fun blockGroup(trackNum: Int, relTime: Int, durMs: Int, text: String): ByteArray {
            val payload = deflateBytes(text.toByteArray(Charsets.UTF_8))
            val blockHeader = byteArrayOf(
                (0x80 or trackNum).toByte(),
                ((relTime ushr 8) and 0xFF).toByte(),
                (relTime and 0xFF).toByte(),
                0x00,
            )
            return elem(
                byteArrayOf(0xA0.toByte()),
                elem(byteArrayOf(0xA1.toByte()), blockHeader + payload) + uint2(byteArrayOf(0x9B.toByte()), durMs),
            )
        }

        val cluster = elem(
            byteArrayOf(0x1F, 0x43, 0xB6.toByte(), 0x75),
            uint2(byteArrayOf(0xE7.toByte()), 1000) +
                blockGroup(2, 500, 2000, "Hello world") +
                blockGroup(3, 600, 2500, "Vanakkam"),
        )
        val ebmlHeader = elem(
            byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()),
            str(byteArrayOf(0x42, 0x82.toByte()), "matroska"),
        )
        val segment = elem(byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67), tracksEl + cluster)

        val tmp = File.createTempFile("demux_zlib_subs_", ".mkv")
        try {
            tmp.writeBytes(ebmlHeader + segment)
            val result = ContainerDemuxer.parseMkv(tmp)
            assertEquals(2, result.tracks.size)
            assertEquals("Hello world", result.cuesByTrack[2]?.singleOrNull()?.text)
            assertEquals(1500L, result.cuesByTrack[2]?.singleOrNull()?.startMs)
            assertEquals(3500L, result.cuesByTrack[2]?.singleOrNull()?.endMs)
            assertEquals("Vanakkam", result.cuesByTrack[3]?.singleOrNull()?.text)
            assertEquals(1600L, result.cuesByTrack[3]?.singleOrNull()?.startMs)
            assertEquals(4100L, result.cuesByTrack[3]?.singleOrNull()?.endMs)
        } finally {
            tmp.delete()
        }
    }
}

