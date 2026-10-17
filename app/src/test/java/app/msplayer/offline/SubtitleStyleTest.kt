package app.msplayer.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleStyleTest {
    @Test fun textStyleOptionsAreBoundedAndPreserveResetDefaults() {
        val options = SubtitleStyle(size = 99, outline = 99, shadow = -2, bottomMargin = 999).options()
        assertTrue(options.contains("--freetype-rel-fontsize=12"))
        assertTrue(options.contains("--freetype-outline-thickness=6"))
        assertTrue(options.contains("--freetype-shadow-opacity=0"))
        assertTrue(options.contains("--sub-margin=0"))
        assertEquals(SubtitleStyle(), SubtitleStyle().copy(size = 4).copy(size = 2))
    }

    @Test fun migrationOnlyReplacesTheOldDefaultTuple() {
        assertEquals(SubtitleStyle(), SubtitleStyle.migrated(2, true, 4, 128, 320))
        val custom = SubtitleStyle.migrated(1, false, 2, 80, 100)
        assertEquals(1, custom.size)
        assertEquals(false, custom.bold)
        assertEquals(100, custom.legacyMargin)
        assertTrue(custom.options().contains("--sub-margin=100"))
    }
}
