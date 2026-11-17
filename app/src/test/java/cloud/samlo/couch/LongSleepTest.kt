package cloud.samlo.couch

import cloud.samlo.couch.home.LongSleep
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongSleepTest {
    private val now = 12 * HOUR

    @Test
    fun wakesOnlyAfterTheChosenHours() {
        assertFalse(LongSleep.due(now - 3 * HOUR, now, 240))
        assertTrue(LongSleep.due(now - 4 * HOUR, now, 240))
        assertTrue(LongSleep.due(now - 9 * HOUR, now, 240))
    }

    @Test
    fun theLimitItselfCounts() = assertTrue(LongSleep.due(now - 4 * HOUR, now, 240))

    @Test
    fun offOrUnknownSleepDoesNothing() {
        assertFalse(LongSleep.due(now - 12 * HOUR, now, 0))
        assertFalse(LongSleep.due(0L, now, 240))
    }

    private companion object {
        const val HOUR = 60 * 60_000L
    }
}
