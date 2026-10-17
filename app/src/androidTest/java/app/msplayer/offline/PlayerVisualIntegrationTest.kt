package app.msplayer.offline

import android.graphics.Bitmap
import android.net.Uri
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Exercises the real selection/effect panels and saves reproducible comparison captures. */
class PlayerVisualIntegrationTest {
    @Test fun multilingualCaptionsAndPlayerPanels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        Store.prefs(context).edit().clear().putString("decoder", "Software").commit()
        fun asset(name: String) = File(context.filesDir, name).also { file ->
            instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        val sample = asset("multi-track.mkv")
        val subtitle = asset("multilingual.srt")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var activity: MainActivity? = null
        scenario.onActivity { activity = it }
        fun await(predicate: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 15000
            while (!predicate() && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue("Player visual state timed out", predicate())
        }
        await { activity?.service != null }
        val service = activity!!.service!!
        val item = MediaItem(Uri.fromFile(sample).toString(), "English / Tamil subtitle comparison", duration = 20000)
        instrumentation.runOnMainSync { activity!!.startMedia(listOf(item), item) }
        await { service.state.value.playing && service.state.value.position > 1500 }
        instrumentation.runOnMainSync { assertTrue(service.addSubtitle(subtitle.absolutePath)) }
        await { service.state.value.subtitles.count { it.first >= 0 } >= 3 }
        instrumentation.runOnMainSync {
            val track = service.state.value.subtitles.last { it.first >= 0 }
            service.subtitleTrack(track.first, track.second)
        }
        Thread.sleep(1200)
        instrumentation.runOnMainSync { service.pause(); activity!!.playerControlsVisible = true }
        fun snapshot(name: String) {
            Thread.sleep(600)
            val image = instrumentation.uiAutomation.takeScreenshot()!!
            File(context.filesDir, "visual-030-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.contentDescription?.toString() == label || node.text?.toString() == label) return node
            for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
            return null
        }
        fun click(label: String) {
            Thread.sleep(500)
            var node = find(instrumentation.uiAutomation.rootInActiveWindow, label)
            assertNotNull("Accessible control: $label", node)
            while (node != null && !node.isClickable) node = node.parent
            assertTrue("Click $label", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        }
        snapshot("player")
        instrumentation.runOnMainSync { activity!!.playerControlsVisible = false }
        snapshot("hidden")
        instrumentation.runOnMainSync { activity!!.playerControlsVisible = true }
        click("Audio tracks")
        snapshot("audio")
        assertNotNull(find(instrumentation.uiAutomation.rootInActiveWindow, "Audio Track"))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        click("Subtitles")
        snapshot("subtitle")
        assertNotNull(find(instrumentation.uiAutomation.rootInActiveWindow, "Subtitle"))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        click("Audio effects")
        snapshot("effects")
        assertNotNull(find(instrumentation.uiAutomation.rootInActiveWindow, "Bass Boost"))
        click("Equalizer")
        snapshot("equalizer")
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        instrumentation.runOnMainSync { activity!!.exitPlayer() }
        snapshot("dashboard")
        instrumentation.runOnMainSync { activity!!.closeApp() }
        scenario.close()
    }
}
