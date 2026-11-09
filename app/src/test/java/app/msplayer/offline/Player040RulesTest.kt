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

    @Test fun snappedRateRespectsDeadZoneAndStops() {
        assertEquals(2.0f, GestureRules.snappedRate(2.0f, 15f, 1080f, deadZonePx = 36f), 0.001f)
        assertTrue(GestureRules.snappedRate(2.0f, 220f, 1080f, deadZonePx = 36f) > 2.0f)
        assertTrue(GestureRules.snappedRate(2.0f, -220f, 1080f, deadZonePx = 36f) < 2.0f)
        assertEquals(4.0f, GestureRules.snappedRate(2.0f, 5000f, 1080f, deadZonePx = 36f), 0.001f)
        assertEquals(0.25f, GestureRules.snappedRate(2.0f, -5000f, 1080f, deadZonePx = 36f), 0.001f)
    }

    @Test fun streamNormalizationWebpageDetectionAndAspectCycle() {
        assertEquals("https://cdn.example.com/movie.mp4", PlaybackRules.normalizeStreamUrl("Check this out: https://cdn.example.com/movie.mp4!"))
        assertEquals("https://example.com/live.m3u8", PlaybackRules.normalizeStreamUrl("example.com/live.m3u8"))
        assertTrue(PlaybackRules.isWebpageWrapperUrl("https://www.youtube.com/shorts/w_ZV24jeRmA"))
        assertFalse(PlaybackRules.isWebpageWrapperUrl("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4"))

        val seq = generateSequence(PlaybackRules.AspectOption.BEST_FIT) { it.next() }.take(7).map { it.label }.toList()
        assertEquals(listOf("Best fit", "Crop", "Stretch", "16:9", "4:3", "Original", "Best fit"), seq)
    }

    @Test fun speedFractionAlignsWithVisualRulerStops() {
        GestureRules.rulerStops.forEachIndexed { idx, stop ->
            val expected = idx.toFloat() / GestureRules.rulerStops.lastIndex
            assertEquals("Stop $stop alignment", expected, GestureRules.fraction(stop), 0.0001f)
        }
        val mapped = GestureRules.rates.map { GestureRules.fraction(it) }
        for (i in 1 until mapped.size) {
            assertTrue(mapped[i] > mapped[i - 1])
        }
        assertEquals(0.5f, GestureRules.fraction(1.75f), 0.0001f)
    }
}
