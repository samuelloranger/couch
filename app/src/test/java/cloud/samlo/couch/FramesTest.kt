package cloud.samlo.couch

import cloud.samlo.couch.measure.Attribution
import cloud.samlo.couch.measure.Benchmark
import cloud.samlo.couch.measure.Frame
import cloud.samlo.couch.measure.fps
import cloud.samlo.couch.measure.Percentiles
import cloud.samlo.couch.measure.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frames belong to the press before them, idle frames to none, and the numbers add up. */
class FramesTest {
    private fun frame(atMs: Long, ms: Long) = Frame(atMs * 1_000_000, ms * 1_000_000, Stage.DRAW)

    @Test fun aFrameBelongsToTheLatestPressInsideTheWindow() {
        val presses = longArrayOf(0, 1_000_000_000)
        assertEquals(0, Attribution.pressFor(frame(100, 10), presses))
        assertEquals(1, Attribution.pressFor(frame(1_200, 10), presses))
        assertEquals(-1, Attribution.pressFor(frame(600, 10), presses)) // after the window: an idle frame
        assertEquals(-1, Attribution.pressFor(frame(-5, 10), longArrayOf(0))) // before any press
    }

    @Test fun percentilesUseNearestRank() {
        val frames = (1L..100L).map { frame(it, it) }
        assertEquals(50, Percentiles.of(frames, 50))
        assertEquals(90, Percentiles.of(frames, 90))
        assertEquals(99, Percentiles.of(frames, 99))
        assertEquals(0, Percentiles.of(emptyList(), 90))
    }

    @Test fun slowMeansOverOneRefresh() {
        assertTrue(frame(0, 17).slow)
        assertTrue(!frame(0, 16).slow)
    }

    @Test fun aRateNeverClaimsMoreThanTheScreenShows() {
        assertEquals(60, fps(16.7f, 60f))   // one refresh: the full rate
        assertEquals(60, fps(5f, 60f))      // room to spare still shows as 60, not 200
        assertEquals(30, fps(33f, 60f))     // two refreshes' worth: half the rate
        assertEquals(120, fps(5f, 120f))    // a faster panel allows more
        assertEquals(60, fps(0f, 60f))      // a zero-length frame can't divide by zero
    }

    @Test fun histogramBucketsBy2msAndCapsTheTail() {
        val h = Benchmark.histogram(listOf(frame(0, 1), frame(0, 3), frame(0, 3), frame(0, 80)))
        assertEquals(1, h[0])
        assertEquals(2, h[1])
        assertEquals(1, h[16])
    }
}
