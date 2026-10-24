package cloud.samlo.couch

import cloud.samlo.couch.godmode.Unlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The About-row counter: seven OKs, no counting over a pending update, no stale sequences. */
class UnlockTest {
    private var t = 0L

    /** Returns what one OK press at the current time produced. */
    private fun press(u: Unlock, pending: Boolean = false): String {
        t += 300
        return when (val o = u.ok(pending)) {
            is Unlock.Outcome.Progress -> "progress:${o.dots}"
            Unlock.Outcome.Unlocked -> "unlocked"
            Unlock.Outcome.Nothing -> "nothing"
        }
    }

    @Test fun seventhPressUnlocks() {
        val u = unlock()
        repeat(6) { assertFalse(press(u) == "unlocked") }
        assertEquals("unlocked", press(u))
    }

    @Test fun theCountdownShowsFromTheThirdPress() {
        val u = unlock()
        assertEquals("nothing", press(u))
        assertEquals("nothing", press(u))
        assertEquals("progress:3", press(u))
        assertEquals("progress:4", press(u))
        assertEquals("nothing", press(u, pending = true)) // pending presses never count
        assertEquals("progress:5", press(u))
    }

    @Test fun theCountResetsAfterThreeSeconds() {
        val u = unlock()
        repeat(4) { press(u) }
        t += 3_500
        assertEquals("nothing", press(u)) // press one of a new sequence
        assertEquals("nothing", press(u)) // press two
        assertEquals("progress:3", press(u)) // press three shows the countdown again
    }

    @Test fun leavingTheRowEndsTheSequence() {
        val u = unlock()
        repeat(5) { press(u) }
        u.reset()
        assertEquals("nothing", press(u))
    }

    @Test fun unlockingStartsANewSequenceFromZero() {
        val u = unlock()
        repeat(7) { press(u) }
        assertEquals("nothing", press(u))
    }

    @Test fun aPendingUpdateNeverUnlocks() {
        val u = unlock()
        repeat(8) { assertEquals("nothing", press(u, pending = true)) }
    }

    private fun unlock() = Unlock(presses = 7, now = { t })
}
