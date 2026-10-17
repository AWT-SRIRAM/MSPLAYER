package app.msplayer.offline

import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackIntegrationTest {
    @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    @Test
    fun contentUriPgsRotationAndPictureInPicture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val values =
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, "MS PLAYER PGS test.mkv")
                put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/x-matroska")
                put(
                    android.provider.MediaStore.Video.Media.RELATIVE_PATH,
                    "Movies/MS PLAYER Tests/",
                )
                put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
            }
        val uri =
            resolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
        instrumentation.context.assets.open("pgs-sample.mkv").use { input ->
            resolver.openOutputStream(uri)!!.use { input.copyTo(it) }
        }
        resolver.update(
            uri,
            android.content.ContentValues().apply {
                put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
            },
            null,
            null,
        )
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var activity: MainActivity? = null
        scenario.onActivity { activity = it }
        awaitCondition { activity?.service != null }
        val service = activity!!.service!!
        val item =
            MediaItem(
                uri.toString(),
                "MS PLAYER PGS test.mkv",
                duration = 10010,
                width = 720,
                height = 480,
            )
        instrumentation.runOnMainSync {
            Store.prefs(context)
                .edit()
                .putString("decoder", "Software")
                .putBoolean("auto_pip", false)
                .commit()
            activity!!.startMedia(listOf(item), item)
        }
        awaitCondition {
            service.state.value.playing && service.state.value.subtitles.any { it.first >= 0 }
        }
        instrumentation.runOnMainSync {
            val track = service.state.value.subtitles.first { it.first >= 0 }
            service.subtitleTrack(track.first, track.second)
        }
        val info = runBlocking { Library.inspect(context, service.vlc, item) }
        assertNotNull(info)
        assertTrue(info!!.subtitles.contains("PGS"))
        instrumentation.runOnMainSync {
            activity!!.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        awaitCondition {
            activity!!.resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        }
        Thread.sleep(700)
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(context.getExternalFilesDir(null), "pgs-screen.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        instrumentation.runOnMainSync {
            service.seek(1000)
            service.play()
            activity!!.enterPip()
        }
        awaitCondition { activity!!.inPip }
        assertTrue(service.state.value.playing)
        instrumentation.runOnMainSync { service.stopPlayback() }
        scenario.close()
        resolver.delete(uri, null, null)
    }

    private fun awaitCondition(timeout: Long = 30000, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < until) {
            if (condition()) return
            Thread.sleep(100)
        }
        assertTrue("Condition was not reached before timeout", condition())
    }

    @Test
    fun nativePlaybackSeekingTracksAndHistory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val assets = instrumentation.context.assets
        val sample = File(context.filesDir, "sample-eac3.mkv")
        assets.open("sample-eac3.mkv").use { input ->
            sample.outputStream().use { input.copyTo(it) }
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var activity: MainActivity? = null
        scenario.onActivity { activity = it }
        awaitCondition { activity?.service != null }
        val service = activity!!.service!!
        val item =
            MediaItem(Uri.fromFile(sample).toString(), "Verified EAC3 sample", duration = 20000)
        instrumentation.runOnMainSync {
            Store.prefs(context)
                .edit()
                .putString("decoder", "Software")
                .putBoolean("resume", true)
                .commit()
            activity!!.startMedia(listOf(item), item)
        }
        awaitCondition {
            android.util.Log.i("MSPlayerTest", "EAC3 state: ${service.state.value}")
            service.state.value.playing &&
                service.state.value.position > 1500 &&
                service.state.value.audioTracks.any { it.first >= 0 }
        }
        Thread.sleep(1500)
        instrumentation.runOnMainSync {
            val media = service.player.media!!
            val stats = media.stats
            android.util.Log.i(
                "MSPlayerTest",
                "decodedAudio=${stats?.decodedAudio} playedAbuffers=${stats?.playedAbuffers} decodedVideo=${stats?.decodedVideo} time=${service.player.time}",
            )
            assertTrue(
                "Native audio decoder produced frames",
                stats != null && stats.decodedAudio > 0,
            )
            assertTrue(
                "Audio output buffers were played",
                stats != null && stats.playedAbuffers > 0,
            )
            media.release()
            service.pause()
            service.seek(7000, fast = false)
        }
        awaitCondition { kotlin.math.abs(service.state.value.position - 7000) < 1500 }
        instrumentation.runOnMainSync {
            service.rate(1.5f)
            assertEquals(1.5f, service.player.rate, 0.01f)
            service.audioDelay(200)
            assertEquals(200000L, service.player.audioDelay)
            service.audioDelay(0)
            service.play()
        }
        awaitCondition { service.state.value.playing }
        instrumentation.runOnMainSync { service.pause() }
        awaitCondition {
            runBlocking {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    Store.db(context).dao().saved(item.uri)?.position ?: 0
                }
            } > 1000
        }
        instrumentation.runOnMainSync { service.open(listOf(item), item) }
        awaitCondition { service.state.value.playing && service.state.value.position > 5500 }
        val controller = android.media.session.MediaController(context, service.mediaSessionToken)
        instrumentation.runOnMainSync { controller.transportControls.pause() }
        awaitCondition { !service.state.value.playing }
        instrumentation.runOnMainSync { controller.transportControls.play() }
        awaitCondition { service.state.value.playing }
        val manager = context.getSystemService(android.media.AudioManager::class.java)
        val focus =
            android.media.AudioFocusRequest.Builder(
                    android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                .setOnAudioFocusChangeListener {}
                .build()
        instrumentation.runOnMainSync {
            assertEquals(
                android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                manager.requestAudioFocus(focus),
            )
        }
        awaitCondition { !service.state.value.playing }
        instrumentation.runOnMainSync { manager.abandonAudioFocusRequest(focus) }
        awaitCondition { service.state.value.playing }
        instrumentation.runOnMainSync { service.stopPlayback() }
        scenario.close()
    }

    @Test
    fun multiTrackAndEmbeddedSubtitles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sample = File(context.filesDir, "multi-track.mkv")
        instrumentation.context.assets.open("multi-track.mkv").use { input ->
            sample.outputStream().use { input.copyTo(it) }
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var activity: MainActivity? = null
        scenario.onActivity { activity = it }
        awaitCondition { activity?.service != null }
        val service = activity!!.service!!
        val item =
            MediaItem(Uri.fromFile(sample).toString(), "AC3 + EAC3 + SRT + ASS", duration = 20000)
        instrumentation.runOnMainSync { activity!!.startMedia(listOf(item), item) }
        awaitCondition {
            service.state.value.playing &&
                service.state.value.audioTracks.count { it.first >= 0 } >= 2 &&
                service.state.value.subtitles.count { it.first >= 0 } >= 2
        }
        instrumentation.runOnMainSync {
            val tracks = service.state.value.audioTracks.filter { it.first >= 0 }
            service.audioTrack(tracks.last().first, tracks.last().second)
            assertEquals(tracks.last().first, service.player.audioTrack)
            val captions = service.state.value.subtitles.filter { it.first >= 0 }
            service.subtitleTrack(captions.last().first, captions.last().second)
            assertEquals(captions.last().first, service.player.spuTrack)
            service.subtitleDelay(300)
            assertEquals(300000L, service.player.spuDelay)
            service.pause()
            service.subtitleStyle(SubtitleStyle(size = 3, bold = true, outline = 4, shadow = 128))
        }
        awaitCondition {
            service.state.value.subtitleDelay == 300L &&
                !service.state.value.playing && !service.state.value.buffering &&
                service.state.value.subtitles.count { it.first >= 0 } >= 2
        }
        instrumentation.runOnMainSync {
            assertEquals(300000L, service.player.spuDelay)
            assertFalse(service.player.isPlaying)
            assertEquals(Store.prefs(context).getString("subtitle_name", null),
                service.state.value.subtitles.firstOrNull { it.first == service.state.value.subtitleTrack }?.second)
            service.play()
        }
        awaitCondition { service.state.value.playing }
        instrumentation.runOnMainSync {
            service.seek(service.state.value.duration - 500, fast = false)
        }
        awaitCondition {
            !service.state.value.playing &&
                service.state.value.position >= service.state.value.duration - 1000
        }
        Thread.sleep(1100)
        assertTrue(Store.db(context).dao().saved(item.uri)!!.watched)
        instrumentation.runOnMainSync { service.play() }
        awaitCondition { service.state.value.playing && service.state.value.position < 2000 }
        instrumentation.runOnMainSync { service.stopPlayback() }
        scenario.close()
    }

    @Test
    fun roomPlaylistPersistenceAndLibraryWithoutPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dao = Store.db(context).dao()
        val item =
            PlaylistEntry().apply {
                playlist = "Test"
                uri = "content://test/item"
                title = "Test media"
                added = 1
            }
        dao.add(item)
        assertEquals("Test media", dao.entries("Test").single().title)
        dao.remove("Test", item.uri)
        assertTrue(dao.entries("Test").isEmpty())
        runBlocking { Library.scan(context, false) } // Revoked permissions must not crash.
    }
}
