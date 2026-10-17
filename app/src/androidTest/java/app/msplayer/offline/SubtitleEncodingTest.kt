package app.msplayer.offline
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
class SubtitleEncodingTest {
    private fun await(label:String,test:()->Boolean) {val end=System.currentTimeMillis()+18000;while(!test()&&System.currentTimeMillis()<end)Thread.sleep(80);assertTrue(label,test())}
    @Test fun equivalentWesternEncodingsRenderEquivalentCaptions() {
        val inst=InstrumentationRegistry.getInstrumentation();val c=inst.targetContext
        val counts=mutableListOf<Int>()
        for((name,encoding) in listOf("western-1252.srt" to "Windows-1252","western-utf8.srt" to "UTF-8","western-utf16.srt" to "UTF-16","western-1252.srt" to "")) {
            Store.prefs(c).edit().clear().putString("decoder","Software").putBoolean("resume",false).commit()
            SubtitleStyle(encoding=encoding).save(c)
            fun asset(n:String)=File(c.filesDir,n).also {f->inst.context.assets.open(n).use {input->f.outputStream().use {input.copyTo(it)}}}
            val movie=asset("surface-probe.mkv");val caption=asset(name)
            val scenario=ActivityScenario.launch(MainActivity::class.java);var a:MainActivity?=null;scenario.onActivity {a=it}
            try {
                await("Service") {a?.service!=null};val s=a!!.service!!;val item=MediaItem(Uri.fromFile(movie).toString(),"Encoding reference",duration=30000)
                inst.runOnMainSync {a!!.startMedia(listOf(item),item)}
                await("Native tracks") {s.timeline.value.position>500&&s.state.value.subtitles.any {it.first>=0}}
                val ids=s.state.value.subtitles.map {it.first}.toSet()
                inst.runOnMainSync {assertTrue(s.addSubtitle(caption.absolutePath))}
                await("Exact new external track") {s.state.value.subtitles.any {it.first>=0&&it.first !in ids}}
                val selected=s.state.value.subtitles.single {it.first>=0&&it.first !in ids}
                inst.runOnMainSync {s.subtitleTrack(selected.first,selected.second);s.seek(2000,false);a!!.playerControlsVisible=false}
                Thread.sleep(800)
                val b=inst.uiAutomation.takeScreenshot()!!
                var pixels=0
                for(y in b.height/2 until b.height)for(x in b.width/8 until b.width*7/8) {
                    val rgb=b.getPixel(x,y);if(minOf(Color.red(rgb),Color.green(rgb),Color.blue(rgb))>185)pixels++
                }
                assertTrue("Caption visible for $encoding",pixels>300);counts.add(pixels)
                File(c.filesDir,"encoding-${encoding.ifEmpty {"Auto"}}.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
            } finally {inst.runOnMainSync {a?.closeApp()};scenario.close()}
        }
        val baseline=counts[1]
        counts.forEach {assertTrue("Equivalent glyph coverage: $counts",it.toFloat()/baseline in .95f..1.05f)}
    }
}
