package app.msplayer.offline

import org.junit.Assert.*
import org.junit.Test

class Player040RulesTest {
    @Test fun speedMappingPreservesBaseAndClampsEnds() {
        listOf(.25f,.5f,.8f,1f,1.5f,2f,2.5f,3f,4f).forEach { base ->
            assertEquals(base,GestureRules.rate(base,0f,1000f),.026f)
            assertTrue(GestureRules.rate(base,-100f,1000f)<=base+.026f)
            assertTrue(GestureRules.rate(base,100f,1000f)>=base-.026f)
        }
        assertEquals(.25f,GestureRules.rate(1f,-10000f,1000f),0f)
        assertEquals(4f,GestureRules.rate(1f,10000f,1000f),0f)
    }
    @Test fun volumeRequiresSecondGestureToCrossUnity() {
        assertEquals(100,GestureRules.volume(80,-600f,1000f))
        assertEquals(200,GestureRules.volume(100,-600f,1000f))
        assertEquals(0,GestureRules.volume(50,800f,1000f))
        assertEquals(100,GestureRules.volume(200,500f,1000f))
    }
    @Test fun lyricsSupportMultistampsAndTamilAndPlainText() {
        val lines=LocalLyrics.parse("[ar:Artist]\n[00:01.25][00:03.500]தமிழ் line\n[00:02.00]Second")
        assertEquals(listOf(1250L,2000L,3500L),lines.map { it.time })
        assertEquals("தமிழ் line",lines.first().text)
        assertEquals(listOf("hello","world"),LocalLyrics.parse("hello\n\nworld").map {it.text})
    }
}
