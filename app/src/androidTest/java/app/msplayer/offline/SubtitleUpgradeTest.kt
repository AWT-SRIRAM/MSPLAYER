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
            val native=s.vlc;val player=s.player
            inst.runOnMainSync {s.subtitleStyle(SubtitleStyle.load(c));s.subtitleStyle(SubtitleStyle.load(c).copy(bottomMargin=10))}
            Thread.sleep(300)
            inst.runOnMainSync {
                assertSame(native,s.vlc);assertSame(player,s.player)
                assertEquals(10,SubtitleStyle.load(c).bottomMargin)
                s.pause()
            }
            await {!s.state.value.playing}
            inst.runOnMainSync {s.subtitleStyle(SubtitleStyle.load(c).copy(size=26))}
            await {s.vlc!==native&&s.state.value.subtitles.any {it.first>=0}&&!s.state.value.buffering}
            assertFalse("Paused intent survives real renderer rebuild",s.state.value.playing)
            assertEquals(item.uri,s.state.value.item?.uri)
        } finally {inst.runOnMainSync {a?.closeApp()};scenario.close()}
    }
}
