package app.msplayer.offline

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.SurfaceView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SubtitleSurfaceProbeTest {
    @Test fun measureNativeSurfaceTransforms() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        check(context.packageName.endsWith(".debug"))
        val file=File(context.filesDir,"surface-probe.mkv")
        inst.context.assets.open(file.name).use { input -> file.outputStream().use {input.copyTo(it)} }
        val report=JSONObject().put("model",Build.MODEL).put("sdk",Build.VERSION.SDK_INT)
        val results=JSONArray()
        fun await(label:String,condition:()->Boolean) {
            val end=SystemClock.elapsedRealtime()+18000
            while(!condition() && SystemClock.elapsedRealtime()<end) Thread.sleep(80)
            assertTrue(label + " state=" + context.filesDir.listFiles()?.joinToString {it.name},condition())
        }
        data class Bounds(val left:Int,val top:Int,val right:Int,val bottom:Int,val pixels:Int)
        fun bounds(b:Bitmap):Bounds {
            var l=b.width;var t=b.height;var r=0;var d=0;var n=0
            for(y in b.height/2 until b.height)for(x in b.width/8 until b.width*7/8) {
                val c=b.getPixel(x,y)
                if(minOf(Color.red(c),Color.green(c),Color.blue(c))>185) {l=minOf(l,x);t=minOf(t,y);r=maxOf(r,x);d=maxOf(d,y);n++}
            }
            return Bounds(l,t,r,d,n)
        }
        fun snapshot(name:String):Bitmap {
            Thread.sleep(260)
            val bitmap=inst.uiAutomation.takeScreenshot()!!
            File(context.filesDir,"probe-$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            return bitmap
        }
        for(mode in listOf("Auto","Software")) {
            Store.prefs(context).edit().clear().putString("decoder",mode).putBoolean("resume",false).commit()
            val scenario=ActivityScenario.launch(MainActivity::class.java)
            var activity:MainActivity?=null;scenario.onActivity {activity=it}
            try {
                await("Service") {activity?.service!=null}
                val service=activity!!.service!!
                val item=MediaItem(Uri.fromFile(file).toString(),"Subtitle surface capability",duration=30000)
                inst.runOnMainSync {activity!!.startMedia(listOf(item),item)}
                await("Visible plain subtitle") {service.state.value.subtitles.any {it.first>=0} && service.timeline.value.position>800}
                inst.runOnMainSync {
                    val track=service.state.value.subtitles.first {it.first>=0};service.subtitleTrack(track.first,track.second)
                    activity!!.playerControlsVisible=false
                }
                Thread.sleep(350)
                lateinit var surface:SurfaceView
                val entry=JSONObject().put("decoderPreference",mode)
                inst.runOnMainSync {
                    surface=activity!!.findViewById(org.videolan.R.id.surface_subtitles)
                    entry.put("surfaceWidth",surface.width).put("surfaceHeight",surface.height).put("surfaceTranslationY",surface.translationY)
                }
                val base=snapshot("${mode}-baseline");val b=bounds(base)
                assertTrue("Caption baseline is visible",b.pixels>300)
                inst.runOnMainSync {surface.pivotX=surface.width/2f;surface.pivotY=surface.height.toFloat();surface.scaleX=1.5f;surface.scaleY=1.5f}
                val scaled=snapshot("${mode}-scaled");val sb=bounds(scaled)
                inst.runOnMainSync {surface.scaleX=1f;surface.scaleY=1f;surface.alpha=.3f}
                val faded=snapshot("${mode}-alpha")
                var baseWhite=0L;var fadeWhite=0L;var baseGreen=0L;var fadeGreen=0L;var greenN=0
                for(y in 0 until base.height)for(x in 0 until base.width) {
                    val c=base.getPixel(x,y);val f=faded.getPixel(x,y)
                    if(y>base.height/2 && minOf(Color.red(c),Color.green(c),Color.blue(c))>185) {baseWhite+=Color.red(c);fadeWhite+=Color.red(f)}
                    if(y<base.height/2 && Color.green(c)>50 && Color.red(c)<20 && Color.blue(c)<20) {baseGreen+=Color.green(c);fadeGreen+=Color.green(f);greenN++}
                }
                inst.runOnMainSync {surface.alpha=1f;surface.scaleX=1f;surface.scaleY=1f;surface.translationY=0f}
                val translated=snapshot("${mode}-translation-zero");val tb=bounds(translated)
                inst.runOnMainSync {service.updateSubtitlePlacement(false)}
                val reset=snapshot("${mode}-reset");val rb=bounds(reset)
                val widthRatio=(sb.right-sb.left+1).toDouble()/(b.right-b.left+1)
                val heightRatio=(sb.bottom-sb.top+1).toDouble()/(b.bottom-b.top+1)
                val alphaRatio=fadeWhite.toDouble()/baseWhite.coerceAtLeast(1)
                val videoRatio=fadeGreen.toDouble()/baseGreen.coerceAtLeast(1)
                entry.put("baseline",JSONObject().put("left",b.left).put("top",b.top).put("right",b.right).put("bottom",b.bottom).put("pixels",b.pixels))
                    .put("scaleWidthRatio",widthRatio).put("scaleHeightRatio",heightRatio)
                    .put("alphaLuminanceRatio",alphaRatio).put("videoGreenRatio",videoRatio).put("videoGreenPixelCount",greenN)
                    .put("translationDeltaPixels",tb.bottom-b.bottom).put("resetBottomDelta",rb.bottom-b.bottom)
                    .put("scaleWorks",widthRatio in 1.35..1.65 && heightRatio in 1.35..1.65)
                    .put("alphaWorks",alphaRatio in .15.. .5 && videoRatio in .95..1.05)
                    .put("translationWorks",tb.bottom>b.bottom+10).put("playingAfterProbe",service.state.value.playing)
                results.put(entry)
                listOf(base,scaled,faded,translated,reset).forEach {it.recycle()}
            } finally {inst.runOnMainSync {activity?.closeApp()};scenario.close()}
        }
        report.put("results",results)
        File(context.filesDir,"subtitle-surface-probe.json").writeText(report.toString(2))
        assertEquals(2,results.length())
    }
}
