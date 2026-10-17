package app.msplayer.offline
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
class SubtitleCalibrationTest {
    private fun await(test:()->Boolean) {val end=System.currentTimeMillis()+20000;while(!test()&&System.currentTimeMillis()<end)Thread.sleep(80);assertTrue(test())}
    @Test fun captureCurrentPresetAndGeometry() {
        val inst=InstrumentationRegistry.getInstrumentation();val c=inst.targetContext;val args=InstrumentationRegistry.getArguments()
        Store.prefs(c).edit().clear().putBoolean("resume",false).putBoolean("native_verbose",true).commit()
        SubtitleStyle(bottomMargin=args.getString("bottom","0").toInt(),font=args.getString("font","Default"),bold=args.getString("bold","true").toBoolean()).save(c)
        val fixture=File(c.filesDir,"calibration-wide.mkv");inst.context.assets.open(fixture.name).use {input->fixture.outputStream().use {input.copyTo(it)}}
        val uri=args.getString("mediaUri") ?: Uri.fromFile(fixture).toString()
        val position=args.getString("position","2000").toLong()
        val item=MediaItem(uri,"Subtitle comparison",duration=if(args.containsKey("mediaUri"))6605757 else 30000)
        val scenario=ActivityScenario.launch(MainActivity::class.java);var a:MainActivity?=null;scenario.onActivity {a=it}
        try {
            await {a?.service!=null};val s=a!!.service!!
            inst.runOnMainSync {a!!.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            await {a!!.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
            Thread.sleep(700)
            inst.runOnMainSync {a!!.startMedia(listOf(item),item)}
            await {s.timeline.value.seekable&&s.state.value.subtitles.any {it.first>=0}}
            val external=args.getString("subtitleAsset")
            val track=if(external!=null) {
                val ids=s.state.value.subtitles.map {it.first}.toSet()
                val f=File(c.filesDir,external);inst.context.assets.open(external).use {input->f.outputStream().use {input.copyTo(it)}}
                inst.runOnMainSync {assertTrue(s.addSubtitle(f.absolutePath))}
                await {s.state.value.subtitles.any {it.first>=0&&it.first !in ids}}
                s.state.value.subtitles.single {it.first>=0&&it.first !in ids}
            } else s.state.value.subtitles.firstOrNull {it.first>=0&&it.second.contains("English",true)} ?: s.state.value.subtitles.first {it.first>=0}
            inst.runOnMainSync {s.subtitleTrack(track.first,track.second);s.seek(position,false);a!!.playerControlsVisible=false}
            await {kotlin.math.abs(s.timeline.value.position-position)<1000}
            Thread.sleep(1800);inst.runOnMainSync {s.pause()};await {!s.state.value.playing};Thread.sleep(300)
            val report=JSONObject().put("position",s.timeline.value.position).put("videoWidth",s.state.value.videoWidth).put("videoHeight",s.state.value.videoHeight)
                .put("selectedTrack",track.second).put("tracks",JSONArray(s.state.value.subtitles.map {it.second}))
            fun capture(name:String) {Thread.sleep(300);val b=inst.uiAutomation.takeScreenshot()!!;File(c.filesDir,"calibration-$name.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
            capture("hidden")
            inst.runOnMainSync {val view=a!!.findViewById<android.view.SurfaceView>(org.videolan.R.id.surface_subtitles);val xy=IntArray(2);view.getLocationOnScreen(xy);report.put("surfaceX",xy[0]).put("surfaceY",xy[1]).put("surfaceWidth",view.width).put("surfaceHeight",view.height);a!!.playerControlsVisible=true}
            capture("controls");File(c.filesDir,"calibration.json").writeText(report.toString(2))
        } finally {inst.runOnMainSync {a?.closeApp()};scenario.close()}
    }
}
