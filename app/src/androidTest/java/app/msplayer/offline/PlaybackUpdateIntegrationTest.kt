package app.msplayer.offline

import android.content.res.Configuration
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlaybackUpdateIntegrationTest {
    private fun await(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 12000
        while (!predicate() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue("Condition timed out", predicate())
    }

    @Test fun orientationEffectsPlacementDiagnosticsAndTaskRemoval() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.filesDir, "update-multi.mkv")
        instrumentation.context.assets.open("multi-track.mkv").use { input -> file.outputStream().use { input.copyTo(it) } }
        Store.prefs(context).edit().clear().commit()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var activity: MainActivity? = null
        scenario.onActivity { activity = it }
        await { activity?.service != null }
        val service = activity!!.service!!
        instrumentation.runOnMainSync {
            service.diagnosticsEnabled(true)
            activity!!.startMedia(listOf(MediaItem(Uri.fromFile(file).toString(), "Update test", duration = 20000)), MediaItem(Uri.fromFile(file).toString(), "Update test", duration = 20000))
        }
        await { service.state.value.playing && service.state.value.videoWidth > 0 }
        await { activity!!.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        await { service.state.value.subtitles.count { it.first >= 0 } >= 2 }
        instrumentation.runOnMainSync {
            service.pause()
            val srt = service.state.value.subtitles.first { it.first >= 0 }
            service.subtitleTrack(srt.first, srt.second)
            activity!!.playerControlsVisible = false
        }
        Thread.sleep(300)
        var surface: android.view.SurfaceView? = null
        var hidden = 0f
        instrumentation.runOnMainSync {
            surface = activity!!.findViewById(org.videolan.R.id.surface_subtitles)
            hidden = surface!!.translationY
            assertEquals(0f, hidden, .01f)
            activity!!.playerControlsVisible = true
        }
        Thread.sleep(300)
        instrumentation.runOnMainSync {
            assertTrue(surface!!.translationY < hidden)
            AudioEffectSettings.presets.forEach { preset ->
                service.effects(AudioEffectSettings(preset))
                assertEquals(preset, service.effectSettings.value.preset)
            }
            service.effects(AudioEffectSettings("Custom", List(10) { 2f }))
            assertEquals("Custom", AudioEffectSettings.load(context).preset)
            service.effects(AudioEffectSettings())
            service.play()
        }
        val subtitle = File(context.filesDir, "multilingual.srt")
        instrumentation.context.assets.open(subtitle.name).use { input -> subtitle.outputStream().use { input.copyTo(it) } }
        instrumentation.runOnMainSync { assertTrue(service.addSubtitle(subtitle.absolutePath)) }
        await { service.state.value.subtitles.count { it.first >= 0 } >= 3 }
        instrumentation.runOnMainSync {
            val authored = service.state.value.subtitles.filter { it.first >= 0 }[1]
            service.subtitleTrack(authored.first, authored.second)
            assertFalse("ASS retains authored placement", service.state.value.plainSubtitle)
            val external = service.state.value.subtitles.last { it.first >= 0 }
            service.subtitleTrack(external.first, external.second)
            assertTrue("External multilingual SRT uses text placement", service.state.value.plainSubtitle)
        }
        Thread.sleep(1400)
        instrumentation.runOnMainSync {
            val report = service.exportDiagnostics().readText()
            assertTrue(report.contains("decoded="))
            assertTrue(report.contains("displayed="))
            service.onTaskRemoved(null)
            assertTrue(service.state.value.closed)
            assertTrue(service.player.isReleased)
            assertTrue(service.vlc.isReleased)
            service.stopPlayback() // Idempotent cleanup.
        }
        await { context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.isEmpty() }
        scenario.close()
    }

    @Test fun metadataAndNativeThumbnailsProduceVisibleFrames() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.filesDir, "thumbnail-test.mkv")
        instrumentation.context.assets.open("multi-track.mkv").use { input -> file.outputStream().use { input.copyTo(it) } }
        val item = MediaItem(Uri.fromFile(file).toString(), "Thumbnail", size = file.length(), duration = 20000)
        Library.videoPlaybackActive.value = false
        runBlocking {
            val frame = Library.thumbnail(context, item)
            assertNotNull("Metadata thumbnail", frame)
            assertFalse(Library.isDark(frame!!))
            val native = NativeThumbnail.read(context, item)
            assertNotNull("LibVLC thumbnail fallback", native)
            assertFalse(Library.isDark(native!!))
            native.recycle()
        }
    }

    @Test fun highBitrateHevcWithEffectsOffAndOn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.filesDir, "high-bitrate-hevc.mkv")
        instrumentation.context.assets.open(file.name).use { input -> file.outputStream().use { input.copyTo(it) } }
        Store.prefs(context).edit().putBoolean("resume", false).putString("decoder", "Auto").putBoolean("native_verbose", false).commit()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var activity: MainActivity? = null
        scenario.onActivity { activity = it }
        await { activity?.service != null }
        val service = activity!!.service!!
        val report = StringBuilder("effect,decoderPreference,decoded,displayed,lost,audioDecoded,audioPlayed\n")
        listOf("Original", "Clarity", "Movie").forEach { effect ->
            instrumentation.runOnMainSync {
                service.effects(AudioEffectSettings(effect))
                val item = MediaItem(Uri.fromFile(file).toString(), "4K60 HEVC10 EAC3 50.8 Mbps", duration = 6000)
                activity!!.startMedia(listOf(item), item)
            }
            await { service.state.value.playing && service.state.value.videoWidth > 0 }
            Thread.sleep(4000)
            instrumentation.runOnMainSync {
                assertNull(service.state.value.error)
                val media = service.player.media!!
                val stats = media.stats!!
                assertTrue("HEVC frames decoded", stats.decodedVideo > 0)
                assertTrue("EAC3 frames decoded", stats.decodedAudio > 0)
                val line = "$effect,${service.state.value.decoder},${stats.decodedVideo},${stats.displayedPictures},${stats.lostPictures},${stats.decodedAudio},${stats.playedAbuffers}"
                report.appendLine(line)
                android.util.Log.i("MSPlayerProfile", line)
                media.release()
                service.pause()
            }
        }
        File(context.filesDir, "performance-031.csv").writeText(report.toString())
        instrumentation.runOnMainSync { activity!!.closeApp() }
        scenario.close()
    }

    @Test fun portraitAndRotationMetadataWithManualOverride() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        Store.prefs(context).edit().putBoolean("resume", false).commit()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var activity: MainActivity? = null
        scenario.onActivity { activity = it }
        await { activity?.service != null }
        val service = activity!!.service!!
        listOf("portrait.mp4", "rotated-portrait.mp4").forEach { name ->
            val file = File(context.filesDir, name)
            instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
            val item = MediaItem(Uri.fromFile(file).toString(), name, duration = 4000)
            instrumentation.runOnMainSync { activity!!.startMedia(listOf(item), item) }
            await { service.state.value.playing && service.state.value.videoHeight > service.state.value.videoWidth }
            await { activity!!.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            instrumentation.runOnMainSync { activity!!.rotateVideo() }
            await { activity!!.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            Thread.sleep(300)
            assertEquals(Configuration.ORIENTATION_LANDSCAPE, activity!!.resources.configuration.orientation)
            instrumentation.runOnMainSync {
                activity!!.exitPlayer()
                assertEquals(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity!!.requestedOrientation)
            }
        }
        instrumentation.runOnMainSync { activity!!.closeApp() }
        scenario.close()
    }
}
