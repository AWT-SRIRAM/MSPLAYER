package app.msplayer.offline
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
class SubtitleUpgradeTest {
    private fun await(test:()->Boolean) {val until=System.currentTimeMillis()+18000;while(!test()&&System.currentTimeMillis()<until)Thread.sleep(80);assertTrue(test())}
    @Test fun migrationAndLivePlacementDoNotRecreateRenderer() {
        val inst=InstrumentationRegistry.getInstrumentation();val c=inst.targetContext
        val p=Store.prefs(c);p.edit().clear().putInt("subtitle_style_version",4).putInt("subtitle_size",2)
            .putBoolean("subtitle_bold",true).putInt("subtitle_outline",1).putInt("subtitle_shadow",64)
            .putInt("subtitle_bottom_percent",6).putString("subtitle_font","Sans Serif").commit()
        assertEquals(SubtitleStyle(font="Sans Serif"),SubtitleStyle.load(c));assertEquals(5,p.getInt("subtitle_style_version",0))
        val file=File(c.filesDir,"surface-probe.mkv");inst.context.assets.open(file.name).use {input->file.outputStream().use {input.copyTo(it)}}
        val scenario=ActivityScenario.launch(MainActivity::class.java);var a:MainActivity?=null;scenario.onActivity {a=it}
        try {
            await {a?.service!=null};val s=a!!.service!!;val item=MediaItem(Uri.fromFile(file).toString(),"Subtitle upgrade",duration=30000)
            inst.runOnMainSync {a!!.startMedia(listOf(item),item)}
            await {s.timeline.value.position>700&&s.state.value.subtitles.any {it.first>=0}}
            await {s.player.isPlaying}
            val native=s.vlc;val player=s.player
            await {
                val decor=a!!.window.decorView
                val surface=a!!.findViewById<android.view.SurfaceView>(org.videolan.R.id.surface_subtitles)
                decor.width>decor.height && surface!=null && surface.height>=decor.height*.9f
            }
            Thread.sleep(400)
            var placement=0f
            inst.runOnMainSync {s.updateSubtitlePlacement(true)};Thread.sleep(100)
            inst.runOnMainSync {placement=a!!.findViewById<android.view.SurfaceView>(org.videolan.R.id.surface_subtitles).translationY;repeat(5){s.updateSubtitlePlacement(true)}}
            Thread.sleep(150)
            inst.runOnMainSync {assertEquals("Placement updates are idempotent",placement,a!!.findViewById<android.view.SurfaceView>(org.videolan.R.id.surface_subtitles).translationY,1.5f)}
            inst.runOnMainSync {
                s.rate(.8f);assertTrue(s.beginTemporarySpeed());s.temporarySpeed(2f)
                s.subtitleStyle(SubtitleStyle.load(c));assertEquals(2f,s.state.value.rate,.01f)
                s.subtitleStyle(SubtitleStyle.load(c).copy(bottomMargin=10));assertEquals(2f,s.state.value.rate,.01f)
                s.endTemporarySpeed();assertEquals(.8f,s.state.value.rate,.01f)
            }
            Thread.sleep(300)
            inst.runOnMainSync {
                assertSame(native,s.vlc);assertSame(player,s.player)
                s.subtitleDelay(150)
                assertEquals(10,SubtitleStyle.load(c).bottomMargin)
                s.pause()
            }
            await {!s.state.value.playing}
            inst.runOnMainSync {s.subtitleStyle(SubtitleStyle.load(c).copy(size=26));s.subtitleTrack(-1,"Disable")}
            await {s.vlc!==native&&s.state.value.subtitles.any {it.first>=0}&&!s.state.value.buffering&&!s.state.value.playing}
            assertFalse("Paused intent survives real renderer rebuild",s.state.value.playing)
            assertEquals(item.uri,s.state.value.item?.uri)
            assertEquals(.8f,s.state.value.rate,.01f);assertEquals(150L,s.state.value.subtitleDelay)
            assertEquals("An explicit Disable during reopen wins over the old selection",-1,s.player.spuTrack)
        } finally {inst.runOnMainSync {a?.closeApp()};scenario.close()}
    }
}
