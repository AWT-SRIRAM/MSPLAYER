package app.msplayer.offline

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Player040IntegrationTest {
    @Test fun temporarySpeedGesturesVolumeFontAndMusic() {
        val inst=InstrumentationRegistry.getInstrumentation();val context=inst.targetContext
        Store.prefs(context).edit().clear().putString("decoder","Software").putBoolean("resume",false).commit()
        fun asset(name:String)=File(context.filesDir,name).also { f -> inst.context.assets.open(name).use { input -> f.outputStream().use {input.copyTo(it)} } }
        val media=asset("multi-track.mkv")
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        var activity:MainActivity?=null;scenario.onActivity { activity=it }
        fun await(label:String,predicate:()->Boolean) {
            val deadline=SystemClock.elapsedRealtime()+15000
            while(!predicate() && SystemClock.elapsedRealtime()<deadline) Thread.sleep(80)
            assertTrue(label,predicate())
        }
        fun capture(name:String) {
            Thread.sleep(180)
            val image=inst.uiAutomation.takeScreenshot()!!
            File(context.filesDir,"visual-040-$name.png").outputStream().use {image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
        }
        fun find(node:AccessibilityNodeInfo?,label:String):AccessibilityNodeInfo? {
            if(node==null)return null
            if(node.contentDescription?.toString()==label || node.text?.toString()==label)return node
            for(i in 0 until node.childCount) find(node.getChild(i),label)?.let{return it}
            return null
        }
        fun click(label:String) {
            var node:AccessibilityNodeInfo?=null
            await("Control $label") {node=find(inst.uiAutomation.rootInActiveWindow,label);node!=null}
            while(node!=null && !node!!.isClickable) node=node!!.parent
            assertTrue("Click $label",node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
            Thread.sleep(250)
        }
        try {
            await("Service") {activity?.service!=null}
            val service=activity!!.service!!
            val item=MediaItem(Uri.fromFile(media).toString(),"MS PLAYER gesture comparison",duration=20000)
            inst.runOnMainSync {activity!!.startMedia(listOf(item),item)}
            await("Playing video") {service.state.value.playing && service.timeline.value.seekable && service.state.value.videoWidth>0}
            inst.runOnMainSync {service.rate(.8f);service.seek(1000,false);activity!!.playerControlsVisible=false}
            Thread.sleep(600);inst.waitForIdleSync()
            val screen=inst.uiAutomation.takeScreenshot()!!;val w=screen.width.toFloat();val h=screen.height.toFloat();screen.recycle()
            var down=0L
            fun motion(action:Int,x:Float,y:Float) {
                val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0)
                inst.sendPointerSync(event);event.recycle()
            }
            down=SystemClock.uptimeMillis();motion(MotionEvent.ACTION_DOWN,w*.5f,h*.48f);Thread.sleep(550)
            motion(MotionEvent.ACTION_MOVE,w*.66f,h*.48f)
            await("Hold drag accelerates") {service.state.value.rate>1f}
            capture("temporary-speed")
            motion(MotionEvent.ACTION_MOVE,w*.36f,h*.48f)
            await("Drag left slows") {service.state.value.rate<.8f}
            motion(MotionEvent.ACTION_UP,w*.36f,h*.48f)
            await("Release restores previous .8x") {kotlin.math.abs(service.state.value.rate-.8f)<.01f}
            inst.runOnMainSync {service.rate(1f);service.seek(1000,false);service.setVolumePercent(50)}
            Thread.sleep(250)
            down=SystemClock.uptimeMillis();motion(MotionEvent.ACTION_DOWN,w*.8f,h*.6f)
            Thread.sleep(40);motion(MotionEvent.ACTION_MOVE,w*.8f,h*.3f);capture("volume-left")
            motion(MotionEvent.ACTION_UP,w*.8f,h*.3f)
            assertTrue("Right swipe adjusts volume",service.volumePercent()>=90)
            down=SystemClock.uptimeMillis();motion(MotionEvent.ACTION_DOWN,w*.2f,h*.6f)
            Thread.sleep(40);motion(MotionEvent.ACTION_MOVE,w*.2f,h*.35f);capture("brightness-right")
            motion(MotionEvent.ACTION_UP,w*.2f,h*.35f)
            inst.runOnMainSync {
                assertEquals(200,service.setVolumePercent(200));assertEquals(200,service.player.volume)
                service.volumeKey(false);assertEquals(195,service.volumePercent())
                service.setVolumePercent(50)
                service.beginTemporarySpeed();service.temporarySpeed(2f);service.pause();assertEquals(1f,service.state.value.rate,.01f)
                val style=SubtitleStyle.load(context).copy(font="Sans Serif")
                service.subtitleStyle(style)
            }
            await("Font retained") {SubtitleStyle.load(context).font=="Sans Serif"}
            inst.runOnMainSync {service.play()}
            await("Font reopen plays") {service.state.value.playing}
            inst.runOnMainSync {service.seek(1000,false);activity!!.playerControlsVisible=true}
            capture("player")
            click("Subtitles");capture("subtitle-panel")
            inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            click("Audio tracks");capture("audio-panel")
            inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            val music=asset("music-test.mp3")
            assertTrue("Embedded ID3 lyrics",LocalLyrics.embedded(context,Uri.fromFile(music).toString())!!.contains("MS PLAYER lyrics"))
            val musicItem=item.copy(uri=Uri.fromFile(music).toString(),audio=true,title="Anime Fair",duration=30000)
            val secondFile=File(context.filesDir,"second-track.mp3");music.copyTo(secondFile,overwrite=true)
            val second=musicItem.copy(uri=Uri.fromFile(secondFile).toString(),title="Second track")
            inst.runOnMainSync {activity!!.startMedia(listOf(musicItem,second),musicItem)}
            await("Audio screen") {service.state.value.item?.audio==true && service.state.value.playing}
            Thread.sleep(700);capture("music")
            click("Favourite")
            await("Favourite persisted") {Store.db(context).dao().entries("Favorites").any { it.uri==musicItem.uri }}
            click("Favourite")
            await("Favourite removed") {Store.db(context).dao().entries("Favorites").none { it.uri==musicItem.uri }}
            inst.runOnMainSync {service.markAB()}
            Thread.sleep(1100)
            inst.runOnMainSync {service.markAB();assertNotNull(service.abPoints.value.second)}
            Thread.sleep(700)
            inst.runOnMainSync {service.markAB();assertNull(service.abPoints.value.first)}
            click("Shuffle");assertTrue(service.shuffleEnabled.value)
            click("Repeat off");assertEquals("all",service.repeatMode.value)
            click("Playing Queue");capture("music-queue")
            click("Second track")
            await("Queue selection plays requested track") {service.state.value.item?.uri==second.uri && service.state.value.playing}
            click("Lyrics");capture("music-lyrics")
            inst.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            inst.runOnMainSync {activity!!.exitPlayer()};capture("music-mini")
        } finally {inst.runOnMainSync {activity?.closeApp()};scenario.close()}
    }
}
