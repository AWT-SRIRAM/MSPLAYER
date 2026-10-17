package app.msplayer.offline

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Run explicitly with -e upgradePhase seed on the prior installed version, then verify after adb install -r. */
class UpgradeVerificationTest {
    @Test fun settingsHistoryAndPlaylistSurviveUpdate() {
        val phase = InstrumentationRegistry.getArguments().getString("upgradePhase")
        assumeTrue("Explicit two-install upgrade check", phase != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = Store.db(context).dao()
        val uri = "file:///upgrade-marker.mkv"
        val prefs = Store.prefs(context)
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        if (phase == "seed") {
            assertEquals(InstrumentationRegistry.getArguments().getString("seedVersion") ?: "0.3.1", version)
            prefs.edit().clear().putString("decoder", "Software").putBoolean("resume", false)
                .putInt("subtitle_size", 2).putBoolean("subtitle_bold", true)
                .putInt("subtitle_outline", 4).putInt("subtitle_shadow", 128).putInt("subtitle_margin", 320).commit()
            dao.save(SavedMedia().apply { this.uri = uri; title = "Upgrade history"; position = 12345; updated = 1 })
            dao.add(PlaylistEntry().apply { playlist = "Upgrade verification"; this.uri = uri; title = "Upgrade playlist"; added = 1 })
        } else {
            assertEquals("verify", phase)
            assertEquals("0.4.0", version)
            assertEquals(12345L, dao.saved(uri)!!.position)
            assertTrue(dao.entries("Upgrade verification").any { it.uri == uri })
            assertEquals("Software", prefs.getString("decoder", ""))
            assertFalse(prefs.getBoolean("resume", true))
            assertEquals(4, prefs.getInt("subtitle_style_version", 0))
            assertEquals(1, prefs.getInt("subtitle_outline", 0))
            assertEquals(6, prefs.getInt("subtitle_bottom_percent", 0))
        }
    }
}
