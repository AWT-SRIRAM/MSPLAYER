package app.msplayer.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AbLoopTest {
    @Test fun noLoopWithoutPointB() = assertNull(PlaybackRules.abLoopTarget(5_000, 1_000, null))
    @Test fun insideLoopDoesNothing() = assertNull(PlaybackRules.abLoopTarget(4_999, 1_000, 5_000))
    @Test fun reachingBJumpsToA() = assertEquals(1_000L, PlaybackRules.abLoopTarget(5_000, 1_000, 5_000))
    @Test fun pastBJumpsToA() = assertEquals(1_000L, PlaybackRules.abLoopTarget(5_400, 1_000, 5_000))
    @Test fun missingAFallsBackToStart() = assertEquals(0L, PlaybackRules.abLoopTarget(6_000, null, 5_000))
}
