package cloud.samlo.couch

import cloud.samlo.couch.home.HomePress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePressTest {
    private val now = 60_000L

    @Test
    fun resumedIsAlwaysHome() = assertTrue(HomePress.whileHome(true, 0L, now))

    @Test
    fun theIntentHandOffPauseCountsAsHome() = assertTrue(HomePress.whileHome(false, now - 15, now))

    @Test
    fun comingBackFromAnAppIsNotHome() {
        assertFalse(HomePress.whileHome(false, now - 5_000, now))
        assertFalse(HomePress.whileHome(false, now - HomePress.HAND_OFF_MS, now))
    }

    @Test
    fun neverPausedIsNotHome() = assertFalse(HomePress.whileHome(false, 0L, now))
}
