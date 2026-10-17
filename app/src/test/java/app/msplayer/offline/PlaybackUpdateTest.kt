package app.msplayer.offline

import org.junit.Assert.*
import org.junit.Test

class PlaybackUpdateTest {
    @Test fun orientationUsesRotationAndSampleAspectRatio() {
        assertEquals(1080 to 1920, PlaybackRules.displayDimensions(1920, 1080, 5))
        assertEquals(768 to 576, PlaybackRules.displayDimensions(720, 576, 0, 16, 15))
        assertEquals(1, PlaybackRules.videoOrientation(1080, 1920))
        assertEquals(2, PlaybackRules.videoOrientation(1920, 1080))
        assertEquals(0, PlaybackRules.videoOrientation(1080, 1080))
        assertEquals(0, PlaybackRules.videoOrientation(0, 0))
    }

    @Test fun effectsHaveHeadroomAndCustomBandsAreBounded() {
        AudioEffectSettings.presets.forEach { preset ->
            val settings = AudioEffectSettings(preset)
            assertEquals(10, settings.gains().size)
            assertTrue(settings.gains().maxOrNull()!! + settings.preamp() <= 0f)
        }
        val custom = AudioEffectSettings("Custom", listOf(99f, -99f))
        assertEquals(12f, custom.gains()[0])
        assertEquals(-12f, custom.gains()[1])
        assertEquals(0f, custom.gains()[9])
        assertEquals(-12f, custom.preamp())
    }
}
