package app.msplayer.offline

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SubtitleRenderingVerificationTest {
    private fun await(predicate: () -> Boolean) {
        val end = System.currentTimeMillis() + 15000
        while (!predicate() && System.currentTimeMillis() < end) Thread.sleep(100)
        assertTrue("Native subtitle setup timed out", predicate())
    }

    @Test fun nativeCaptionActuallyProducesVisiblePixels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        Store.prefs(context).edit().clear().putString("decoder", "Software").putBoolean("native_verbose", true).commit()
        val sample = File(context.filesDir, "caption-render-test.mkv")
        instrumentation.context.assets.open("multi-track.mkv").use { input -> sample.outputStream().use { input.copyTo(it) } }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var activity: MainActivity? = null
        scenario.onActivity { activity = it }
        await { activity?.service != null }
        val service = activity!!.service!!
        val item = MediaItem(Uri.fromFile(sample).toString(), "Subtitle rendering test", duration = 20000)
        instrumentation.runOnMainSync { activity!!.startMedia(listOf(item), item) }
        await { service.state.value.playing && service.state.value.subtitles.count { it.first >= 0 } >= 2 && service.state.value.position > 1500 }
        val srt = service.state.value.subtitles.first { it.first >= 0 }
        fun snapshot(name: String): Bitmap {
            Thread.sleep(500)
            val image = instrumentation.uiAutomation.takeScreenshot()!!
            File(context.filesDir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return image
        }
        fun whitePixels(image: Bitmap, bottomPercent: Int = 99): Int {
            var total = 0
            for (y in image.height * 35 / 100 until image.height * bottomPercent / 100) for (x in image.width / 4 until image.width * 3 / 4) {
                val pixel = image.getPixel(x, y)
                val red = android.graphics.Color.red(pixel)
                val green = android.graphics.Color.green(pixel)
                val blue = android.graphics.Color.blue(pixel)
                // The player scrim dims native captions when controls are visible.
                if (minOf(red, green, blue) > 160 && maxOf(red, green, blue) - minOf(red, green, blue) < 20) total++
            }
            return total
        }
        var enabled = 0
        listOf("caption-default" to SubtitleStyle(), "caption-legacy" to SubtitleStyle(outline = 4, shadow = 128, legacyMargin = 100), "caption-zero" to SubtitleStyle(bottomMargin = 0)).forEach { (name, style) ->
            instrumentation.runOnMainSync { service.pause(); service.subtitleStyle(style) }
            Thread.sleep(300)
            await { !service.state.value.buffering && service.state.value.subtitles.count { it.first >= 0 } >= 2 }
            val before = service.state.value.position
            instrumentation.runOnMainSync { service.subtitleTrack(srt.first, srt.second); service.play() }
            await { service.state.value.playing && service.state.value.position > before + 300 }
            instrumentation.runOnMainSync { service.pause(); activity!!.playerControlsVisible = false }
            val bitmap = snapshot(name)
            if (name == "caption-zero") enabled = whitePixels(bitmap)
            android.util.Log.i("MSPlayerCaption", "$name white=${whitePixels(bitmap)} state=${service.state.value}")
            bitmap.recycle()
            if (name == "caption-default") {
                instrumentation.runOnMainSync { activity!!.playerControlsVisible = true }
                val controlsImage = snapshot("caption-controls")
                assertTrue("Caption stays above the seek bar", whitePixels(controlsImage, 72) > 300)
                controlsImage.recycle()
                instrumentation.runOnMainSync { activity!!.playerControlsVisible = false }
            }
        }
        instrumentation.runOnMainSync { service.subtitleTrack(-1, "Disable"); service.play() }
        Thread.sleep(1000)
        instrumentation.runOnMainSync { service.pause() }
        val baseline = snapshot("caption-disabled")
        val disabled = whitePixels(baseline)
        baseline.recycle()
        instrumentation.runOnMainSync { activity!!.closeApp() }
        scenario.close()
        assertTrue("A selected SRT must add visible caption pixels: enabled=$enabled disabled=$disabled", enabled > disabled + 300)
    }
}
