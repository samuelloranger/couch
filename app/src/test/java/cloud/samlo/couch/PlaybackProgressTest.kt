package cloud.samlo.couch

import cloud.samlo.couch.ui.PlaybackProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackProgressTest {
    @Test
    fun positionMovesOnWhilePlaying() {
        assertEquals(3_000L, PlaybackProgress.position(1_000, updatedAt = 1_000, speed = 1f, playing = true, now = 3_000))
        assertEquals(5_000L, PlaybackProgress.position(1_000, updatedAt = 1_000, speed = 2f, playing = true, now = 3_000))
    }

    @Test
    fun positionStandsStillWhenPausedOrUnreported() {
        assertEquals(1_000L, PlaybackProgress.position(1_000, updatedAt = 1_000, speed = 1f, playing = false, now = 9_000))
        assertEquals(1_000L, PlaybackProgress.position(1_000, updatedAt = 0, speed = 1f, playing = true, now = 9_000))
        // A report from the future can't pull the bar backwards.
        assertEquals(1_000L, PlaybackProgress.position(1_000, updatedAt = 9_000, speed = 1f, playing = true, now = 5_000))
    }

    @Test
    fun fractionIsClampedAndZeroWithoutADuration() {
        assertEquals(0.5f, PlaybackProgress.fraction(60_000, 120_000), 0.001f)
        assertEquals(1f, PlaybackProgress.fraction(180_000, 120_000), 0.001f)
        assertEquals(0f, PlaybackProgress.fraction(60_000, 0), 0.001f)
    }

    @Test
    fun clockReadsMinutesThenHours() {
        assertEquals("3:12", PlaybackProgress.clock(192_000))
        assertEquals("0:07", PlaybackProgress.clock(7_000))
        assertEquals("1:02:03", PlaybackProgress.clock(3_723_000))
        assertEquals("0:00", PlaybackProgress.clock(-5_000))
    }
}
