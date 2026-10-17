package app.msplayer.offline

import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PlaybackRepairIntegrationTest {
    @Test fun actualTimelineTapsDragAndTemporaryMediaPlayback() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        Store.prefs(context).edit().clear().putBoolean("resume", false).putString("decoder", "Software").commit()
        val file = File(context.filesDir, ".pending-123-movie.mkv")
        inst.context.assets.open("multi-track.mkv").use { input -> file.outputStream().use { input.copyTo(it) } }
        val item = runBlocking { Library.opened(context, Uri.fromFile(file)) }
        assertTrue(PlaybackRules.mediaFile(null, item.title))
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var activity: MainActivity? = null
        scenario.onActivity { activity = it }
        fun await(label: String, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 18000
            while (!predicate() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            assertTrue(label, predicate())
        }
        try {
            await("Service ready") { activity?.service != null }
            val service = activity!!.service!!
            inst.runOnMainSync { service.diagnosticsEnabled(true); activity!!.startMedia(listOf(item), item) }
            await("Temporary file decoded") { service.state.value.playing && service.timeline.value.seekable && service.player.time > 1000 }
            inst.runOnMainSync { service.pause(); activity!!.playerControlsVisible = true }
            await("Paused") { !service.state.value.playing }
            assertTrue("Paused playback reserves decoding resources", Library.videoPlaybackActive.value)
            val nativePlayer = service.player
            fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.contentDescription?.toString()?.contains("Playback timeline") == true) return node
                for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
                return null
            }
            inst.waitForIdleSync()
            var node: AccessibilityNodeInfo? = null
            await("Timeline window ready after rotation") {
                node = find(inst.uiAutomation.rootInActiveWindow)
                node != null
            }
            if (node == null) {
                fun dump(n: AccessibilityNodeInfo?, depth: Int = 0) {
                    if (n == null) return
                    android.util.Log.i("RepairUI", "${n.className} text=${n.text} desc=${n.contentDescription}")
                    for(i in 0 until n.childCount) dump(n.getChild(i),depth+1)
                }
                dump(inst.uiAutomation.rootInActiveWindow)
                val capture=inst.uiAutomation.takeScreenshot()!!
                File(context.filesDir,"repair-failure.png").outputStream().use { capture.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; capture.recycle()
            }
            assertNotNull("Accessible timeline", node)
            val rect = Rect(); node!!.getBoundsInScreen(rect)
            assertTrue(rect.width() > 100)
            fun point(f: Float) = (rect.left + rect.width() * f) to rect.exactCenterY()
            fun motion(action: Int, x: Float, y: Float, down: Long) {
                val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                e.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                inst.sendPointerSync(e); e.recycle()
            }
            fun tap(f: Float) {
                val (x,y) = point(f); val down = SystemClock.uptimeMillis()
                motion(MotionEvent.ACTION_DOWN,x,y,down); Thread.sleep(40); motion(MotionEvent.ACTION_UP,x,y,down)
            }
            tap(.78f)
            await("Tap seeks forward in native decoder") { service.player.time > 8000 }
            tap(.12f)
            await("Tap seeks backwards in native decoder") { service.player.time < 4000 }
            tap(.30f)
            await("Same-keyframe tap receives one precise correction") { service.player.time in 4000..8000 }
            val down = SystemClock.uptimeMillis(); val (x,y) = point(.15f)
            motion(MotionEvent.ACTION_DOWN,x,y,down)
            for (i in 1..8) { val (next,_) = point(.15f + i*.075f); Thread.sleep(25); motion(MotionEvent.ACTION_MOVE,next,y,down) }
            val (last,_) = point(.75f); motion(MotionEvent.ACTION_UP,last,y,down)
            await("Drag seeks in native decoder") { service.player.time > 8000 }
            assertSame("Seeking keeps renderer", nativePlayer, service.player)
            assertFalse("Paused seeking remains paused", service.state.value.playing)
            inst.runOnMainSync {
                assertTrue("Seek clamps negative values", service.seek(-1000))
                service.effects(AudioEffectSettings("Clarity"))
                assertEquals("Clarity", AudioEffectSettings.load(context).preset)
                val report = service.exportDiagnostics()
                val text = report.readText()
                assertTrue("Native requests logged", text.contains("seek-request"))
                assertTrue("Fast seek used", text.contains("fast=true"))
                File(context.filesDir, "repair-031-diagnostics.txt").writeText(text)
            }
            val image = inst.uiAutomation.takeScreenshot()!!
            File(context.filesDir, "repair-031-timeline.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }; image.recycle()
        } finally {
            inst.runOnMainSync { activity?.closeApp() }
            scenario.close()
        }
    }

    @Test fun extensionlessPendingHeaderAndInvalidFileAreHandled() {
        val inst = InstrumentationRegistry.getInstrumentation(); val context = inst.targetContext
        val file = File(context.filesDir, ".pending-456")
        inst.context.assets.open("multi-track.mkv").use { input -> file.outputStream().use { input.copyTo(it) } }
        assertEquals("video/x-matroska", Library.sniff(context, Uri.fromFile(file)))
        val bad = File(context.filesDir, "invalid.mkv.pending").apply { writeText("not a video") }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var activity: MainActivity? = null; scenario.onActivity { activity = it }
        val deadline = SystemClock.elapsedRealtime()+12000
        while (activity?.service == null && SystemClock.elapsedRealtime()<deadline) Thread.sleep(100)
        val service = activity!!.service!!
        try {
            inst.runOnMainSync {
                val item=MediaItem(Uri.fromFile(bad).toString(), bad.name)
                activity!!.startMedia(listOf(item),item)
            }
            val until=SystemClock.elapsedRealtime()+15000
            while(service.state.value.error==null && SystemClock.elapsedRealtime()<until) Thread.sleep(100)
            assertNotNull("Incomplete download gives error",service.state.value.error)
            assertTrue(service.state.value.error!!.contains("incomplete"))
            assertFalse(service.state.value.playing)
        } finally { inst.runOnMainSync { activity!!.closeApp() }; scenario.close() }
    }
}
