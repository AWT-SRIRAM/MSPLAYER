package app.msplayer.offline
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
class SubtitleTextDialogTest {
    @Test fun resetIsDraftOnlyAndCancelDiscardsIt() {
        val inst=InstrumentationRegistry.getInstrumentation();val c=inst.targetContext
        val original=SubtitleStyle(size=31,outline=3,shadow=70,bottomMargin=9);original.save(c)
        var applied:SubtitleStyle?=null;var cancelled=false
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        fun show() {Thread.sleep(1200);scenario.onActivity {a->a.setContent {MaterialTheme {SubtitleTextDialog(original,{cancelled=true},{applied=it})}}};Thread.sleep(400)}
        fun find(n:AccessibilityNodeInfo?,s:String):AccessibilityNodeInfo? {
            if(n==null)return null;if(n.text?.toString()==s)return n
            for(i in 0 until n.childCount)find(n.getChild(i),s)?.let{return it};return null
        }
        fun click(s:String) {
            var n=find(inst.uiAutomation.rootInActiveWindow,s)
            val until=System.currentTimeMillis()+6000
            while(n==null&&System.currentTimeMillis()<until) {Thread.sleep(100);n=find(inst.uiAutomation.rootInActiveWindow,s)}
            assertNotNull(s,n)
            while(n!=null&&!n.isClickable)n=n.parent
            assertTrue(n?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true);Thread.sleep(250)
        }
        try {
            show();click("RESET");assertEquals(original,SubtitleStyle.load(c));assertNull(applied)
            inst.uiAutomation.takeScreenshot()!!.let {b->File(c.filesDir,"subtitle-text-dialog.png").outputStream().use {b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
            click("CANCEL");assertTrue(cancelled);assertEquals(original,SubtitleStyle.load(c))
            show();click("RESET");click("OK");assertEquals(SubtitleStyle(),applied)
            assertEquals("Callback alone does not persist before caller applies",original,SubtitleStyle.load(c))
        } finally {scenario.onActivity {it.closeApp()};scenario.close()}
    }
}
