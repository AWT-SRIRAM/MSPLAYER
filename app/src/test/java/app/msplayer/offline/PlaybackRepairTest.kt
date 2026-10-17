package app.msplayer.offline

import org.junit.Assert.*
import org.junit.Test

class PlaybackRepairTest {
    @Test fun temporaryNamesRetainMediaType() {
        assertTrue(PlaybackRules.mediaFile("application/octet-stream", ".pending-123-movie.mkv"))
        assertTrue(PlaybackRules.mediaFile(null, "movie.MP4.pending"))
        assertTrue(PlaybackRules.audioFile(null, "voice.eac3.part"))
        assertFalse(PlaybackRules.mediaFile(null, "notes.txt.pending"))
        assertTrue(PlaybackRules.temporaryFile(".pending-123"))
        assertFalse(PlaybackRules.temporaryFile("ordinary.mkv"))
    }
    @Test fun temporaryHeaderProbeRecognizesOnlySupportedSignatures() {
        assertEquals("video/x-matroska", PlaybackRules.sniffMedia(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte())))
        assertEquals("video/mp4", PlaybackRules.sniffMedia(byteArrayOf(0,0,0,24) + "ftypisom".toByteArray()))
        assertEquals("audio/flac", PlaybackRules.sniffMedia("fLaC".toByteArray()))
        assertEquals("audio/wav", PlaybackRules.sniffMedia("RIFF1234WAVE".toByteArray()))
        assertNull(PlaybackRules.sniffMedia("plain text".toByteArray()))
        assertNull(PlaybackRules.sniffMedia(byteArrayOf()))
    }
    @Test fun clarityKeepsSpeechBandsAtUnityWithoutPositiveGain() {
        val clarity = AudioEffectSettings("Clarity")
        assertEquals(0f, clarity.preamp(), 0f)
        assertTrue(clarity.gains().all { it <= 0f })
        assertTrue(clarity.gains().slice(3..7).all { it == 0f })
        assertTrue(clarity.gains()[0] < 0f)
    }
}
