package app.msplayer.offline

import org.junit.Assert.*
import org.junit.Test

class AutoSubEngineTest {

    @Test
    fun testOversizedWordSplitting() {
        val word = "SupercalifragilisticexpialidociousLongWordIndeed"
        val chunks = AutoSubEngine.splitOversizedWord(word, 0.0, 5.0, 20)
        assertTrue(chunks.size >= 2)
        // Verify all chunks except possibly last end with hyphen
        for (i in 0 until chunks.size - 1) {
            assertTrue(chunks[i].word.endsWith("-"))
            assertTrue(chunks[i].word.length <= 20)
        }
        // Timing must be continuous
        assertEquals(0.0, chunks.first().start, 0.001)
        assertEquals(5.0, chunks.last().end, 0.001)
    }

    @Test
    fun testFindBestSplitPrefersSentencePunctuation() {
        val words = listOf("This", "is", "great.", "We", "are", "ready", "now", "here")
        val split = AutoSubEngine.findBestSplit(words, 38)
        assertEquals(3, split) // split after "great."
    }

    @Test
    fun testFindBestSplitPrefersConjunction() {
        val words = listOf("I", "was", "happy", "because", "the", "sun", "shined")
        val split = AutoSubEngine.findBestSplit(words, 38)
        assertEquals(3, split) // split before "because"
    }

    @Test
    fun testBalancedSrtBlocksAndTimingRules() {
        val words = listOf(
            AutoSubEngine.WordInfo("Welcome", 0.0, 0.5),
            AutoSubEngine.WordInfo("to", 0.5, 0.8),
            AutoSubEngine.WordInfo("MS", 0.8, 1.2),
            AutoSubEngine.WordInfo("PLAYER", 1.2, 1.8),
            // Silence gap > 0.4s to force block flush
            AutoSubEngine.WordInfo("Enjoy", 2.5, 3.0),
            AutoSubEngine.WordInfo("the", 3.0, 3.3),
            AutoSubEngine.WordInfo("movie!", 3.3, 3.8)
        )

        val rawBlocks = AutoSubEngine.createBalancedSrtBlocks(words)
        assertEquals(2, rawBlocks.size)
        assertEquals("Welcome to MS PLAYER", rawBlocks[0].text)
        assertEquals("Enjoy the movie!", rawBlocks[1].text)

        val timedBlocks = AutoSubEngine.applyTimingRules(rawBlocks)
        assertEquals(2, timedBlocks.size)

        // Min duration is 1.0s, frame gap is 0.08s
        assertTrue(timedBlocks[0].end - timedBlocks[0].start >= AutoSubEngine.MIN_DURATION)
        assertTrue(timedBlocks[1].start >= timedBlocks[0].end + AutoSubEngine.FRAME_GAP)

        val srtOutput = AutoSubEngine.formatSrt(timedBlocks)
        assertTrue(srtOutput.contains("Welcome to MS PLAYER"))
        assertTrue(srtOutput.contains("Enjoy the movie!"))
        assertTrue(srtOutput.contains("-->"))
    }

    @Test
    fun testCpsExtensionLimit() {
        // Very fast text exceeding MAX_CPS (20 chars/sec)
        val denseText = "A".repeat(80)
        val blocks = listOf(AutoSubEngine.SubtitleBlock(0.0, 1.0, denseText))
        val timed = AutoSubEngine.applyTimingRules(blocks)

        // Raw duration is 1.0s. Ideal duration = 80 / 20 = 4.0s. Extension = 3.0s, capped at CPS_EXTENSION_LIMIT (1.5s) + END_PADDING (0.1s) = 2.6s
        assertEquals(1, timed.size)
        assertEquals(2.6, timed[0].end - timed[0].start, 0.01)
    }

    @Test
    fun testRapidDialogueZeroCascadeDrift() {
        val blocks = (0 until 50).map { i ->
            val start = i * 0.45
            AutoSubEngine.SubtitleBlock(start, start + 0.30, "Line $i ok")
        }
        val timed = AutoSubEngine.applyTimingRules(blocks)
        assertEquals(50, timed.size)
        assertEquals(49 * 0.45, timed.last().start, 0.001)
        for (i in 0 until timed.lastIndex) {
            assertEquals(i * 0.45, timed[i].start, 0.001)
            assertTrue(timed[i].end <= timed[i + 1].start - AutoSubEngine.FRAME_GAP + 0.001)
        }
    }

    @Test
    fun testSentencePunctuationSplitsBlocks() {
        val words = listOf(
            AutoSubEngine.WordInfo("How", 0.0, 0.3),
            AutoSubEngine.WordInfo("are", 0.3, 0.6),
            AutoSubEngine.WordInfo("you", 0.6, 0.9),
            AutoSubEngine.WordInfo("doing", 0.9, 1.3),
            AutoSubEngine.WordInfo("today?", 1.3, 1.7),
            AutoSubEngine.WordInfo("Fine", 1.75, 2.0),
            AutoSubEngine.WordInfo("thanks.", 2.0, 2.5),
        )
        val blocks = AutoSubEngine.createBalancedSrtBlocks(words)
        assertEquals(2, blocks.size)
        assertEquals("How are you doing today?", blocks[0].text)
        assertEquals("Fine thanks.", blocks[1].text)
    }
}

