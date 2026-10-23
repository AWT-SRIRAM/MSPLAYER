package app.msplayer.offline

import org.junit.Assert.*
import org.junit.Test

class PlaybackRulesTest {
    @Test
    fun pinchScaleIsRelativeToScreenFitForBothSmallAndLargeVideos() {
        assertEquals(3.75f, PlaybackRules.videoScale(640f, 360f, 1920f, 1080f, 1.25f), .001f)
        assertEquals(.625f, PlaybackRules.videoScale(3840f, 2160f, 1920f, 1080f, 1.25f), .001f)
        assertEquals(0f, PlaybackRules.videoScale(0f, 0f, 1920f, 1080f, 1.25f), .001f)
    }

    @Test
    fun genericProvidersDoNotHideSupportedMedia() {
        assertTrue(PlaybackRules.mediaFile("application/octet-stream", "movie.MKV"))
        assertTrue(PlaybackRules.audioFile(null, "sound.EAC3"))
        assertTrue(PlaybackRules.audioFile("application/octet-stream", "sound.ac3"))
        assertFalse(PlaybackRules.audioFile("video/ogg", "movie.ogg"))
        assertFalse(PlaybackRules.mediaFile("application/pdf", "document.pdf"))
    }

    @Test
    fun seeksClampAtFileBoundaries() {
        assertEquals(0L, PlaybackRules.safeSeek(-20000, 3600000))
        assertEquals(3600000L, PlaybackRules.safeSeek(Long.MAX_VALUE, 3600000))
        assertEquals(0L, PlaybackRules.safeSeek(90000, -1))
    }

    @Test
    fun largePositionsDoNotOverflow() {
        assertEquals(21_600_000L, PlaybackRules.safeSeek(21_600_000, 36_000_000))
        assertEquals(9_000_000_000L, PlaybackRules.resume(9_000_000_000, 12_000_000_000, false))
    }

    @Test
    fun completedFilesRestartAndUnfinishedFilesResume() {
        assertEquals(0L, PlaybackRules.resume(3599500, 3600000, false))
        assertEquals(0L, PlaybackRules.resume(10000, 3600000, true))
        assertEquals(120000L, PlaybackRules.resume(120000, 3600000, false))
        assertFalse(PlaybackRules.completed(0, 0))
        assertFalse(PlaybackRules.completed(1, 1000))
    }

    @Test
    fun subtitleBadgesComeFromParsedCodecs() {
        assertEquals("PGS", PlaybackRules.subtitleBadge("pgss"))
        assertEquals("PGS", PlaybackRules.subtitleBadge("bdpg"))
        assertEquals("bdpg", PlaybackRules.fourcc(0x67706462))
        assertEquals("ASS", PlaybackRules.subtitleBadge("ssa "))
        assertEquals("SRT", PlaybackRules.subtitleBadge("subt"))
    }

    @Test
    fun cueTimestampsParseAndNavigateCorrectly() {
        val srtSample = """
            1
            00:01:20,123 --> 00:01:24,456
            Hello world

            2
            00:02:15.500 --> 00:02:18.000
            Second cue
        """.trimIndent()
        val cues = PlaybackRules.parseCueTimestamps(srtSample)
        assertEquals(2, cues.size)
        assertEquals(80123L, cues[0])
        assertEquals(135500L, cues[1])
        assertEquals(80123L, PlaybackRules.findNextCue(cues, 0L))
        assertEquals(135500L, PlaybackRules.findNextCue(cues, 80123L))
        assertEquals(null, PlaybackRules.findNextCue(cues, 140000L))
        assertEquals(80123L, PlaybackRules.findPrevCue(cues, 135500L))
        assertEquals(null, PlaybackRules.findPrevCue(cues, 80123L))
    }

    @Test
    fun streamUrlValidationDetectsSupportedProtocols() {
        assertTrue(PlaybackRules.isStreamUrl("https://example.com/live.m3u8"))
        assertTrue(PlaybackRules.isStreamUrl("http://example.com/video.mp4"))
        assertTrue(PlaybackRules.isStreamUrl("rtsp://192.168.1.50:554/ch0"))
        assertTrue(PlaybackRules.isStreamUrl("rtmp://live.stream.net/app/key"))
        assertFalse(PlaybackRules.isStreamUrl("content://media/external/video/media/123"))
        assertFalse(PlaybackRules.isStreamUrl("/storage/emulated/0/DCIM/video.mp4"))
    }
}
