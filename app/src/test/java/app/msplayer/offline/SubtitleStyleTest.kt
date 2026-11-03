package app.msplayer.offline
import org.junit.Assert.*
import org.junit.Test
class SubtitleStyleTest {
    private val font=SubtitleFont("Test","sans-serif",true)
    @Test fun defaultsAndBounds() {
        val d=SubtitleStyle();assertEquals(23,d.size);assertEquals(100,d.textScale);assertEquals(2,d.outline)
        assertEquals(100,d.lineSpacing);assertEquals(2,d.shadowOffset)
        val o=SubtitleStyle(size=99,scale=999,outline=99,shadow=-2,shadowOffset=99).renderOptions(font)
        assertTrue(o.contains("--freetype-rel-fontsize=17"));assertTrue(o.contains("--sub-text-scale=500"))
        assertTrue(o.contains("--freetype-outline-thickness=6"));assertTrue(o.contains("--freetype-shadow-opacity=0"))
        assertTrue(o.contains("--freetype-shadow-distance=10"));assertTrue(o.contains("--freetype-shadow-angle=45"))
        assertEquals(d.renderOptions(font),d.copy(bottomMargin=30).renderOptions(font))
    }
    @Test fun encodingsAreBoundedAndUnicodeIsNotDecodedTwice() {
        assertTrue(SubtitleStyle(encoding="UTF-16").renderOptions(font).contains("--subsdec-encoding=UTF-8"))
        assertTrue(SubtitleStyle(encoding="unexpected").renderOptions(font).contains("--subsdec-encoding="))
        assertEquals(10,SubtitleStyle.encodings.size)
    }
    @Test fun migrationTestsEveryStepAndPreservesCustomLook() {
        val expected=listOf(71,77,85,106,142)
        val legacy=listOf(85,94,106,142,283)
        for(i in 0..4) {
            val c=SubtitleStyle.migrateV4(i,false,3,80,9,null,"Arial")
            assertEquals(expected[i],c.scale);assertEquals(3,c.outline);assertEquals(80,c.shadow)
            assertFalse(c.compensateNativeInset);assertEquals(220,c.borderOpacity);assertEquals(9,c.bottomMargin);assertEquals("Arial",c.font)
            assertEquals(legacy[i],SubtitleStyle.migrateV4(i,false,3,80,9,100,"Arial").scale)
        }
        assertEquals(SubtitleStyle(font="Default"),SubtitleStyle.migrateV4(2,true,1,64,6,null,"Default"))
        assertEquals(85,SubtitleStyle.migrateV4(2,true,1,64,7,null,"Roboto").scale)
        assertEquals(SubtitleStyle(),SubtitleStyle.migrated(2,true,4,128,320))
        assertEquals(100,SubtitleStyle.migrated(1,false,2,80,100).legacyMargin)
    }
}
