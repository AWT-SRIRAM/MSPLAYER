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
        assertTrue(PlaybackRules.mediaFile(null, "movie.mkv.pending-1795051503"))
        assertEquals("mkv", PlaybackRules.containerExtensionHint("movie.mkv.pending-1795051503"))
        assertEquals("mkv", PlaybackRules.containerExtensionHint(".pending-1795051503-SH3LBY", "video/x-matroska"))
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
    @Test fun clarityBoostsVocalArticulationWithoutClipping() {
        val clarity = AudioEffectSettings("Clarity")
        val gains = clarity.gains()
        assertEquals(10, gains.size)
        // Sub-bass mud is trimmed
        assertTrue(gains[0] < 0f && gains[1] < 0f)
        // Speech presence & clarity bands (1kHz..8kHz) receive positive clarity boost
        assertTrue(gains[5] > 0f && gains[6] > 0f && gains[7] > 0f && gains[8] > 0f)
        // Preamp keeps max boosted band safely at or below 0 dBFS headroom
        assertTrue(gains.maxOrNull()!! + clarity.preamp() <= 0f)
    }
}
