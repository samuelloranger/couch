package cloud.samlo.couch

import cloud.samlo.couch.data.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UsageTest {
    private val day = 24 * HOUR
    // Seven days starting at 0, so day i spans [i * day, (i + 1) * day).
    private val starts = LongArray(Usage.DAYS + 1) { it * day }
    private val resumed = Usage.Tally.RESUMED
    private val paused = Usage.Tally.PAUSED

    @Test
    fun timeRunsFromResumeToPause() {
        val tally = Usage.Tally(starts, "couch")
        tally.add(resumed, "tv.app", 6 * day + HOUR)
        tally.add(paused, "tv.app", 6 * day + 3 * HOUR)
        val usage = tally.finish(6 * day + 5 * HOUR).getValue("tv.app")
        assertEquals(2 * HOUR, usage.perDayMs[6])
        assertEquals(2 * HOUR, usage.weekMs)
        assertEquals(1, usage.opens)
        assertEquals(6 * day + 3 * HOUR, usage.lastUsed)
    }

    @Test
    fun aSessionAcrossMidnightCountsOnBothDays() {
        val tally = Usage.Tally(starts, "couch")
        tally.add(resumed, "tv.app", 5 * day - HOUR)
        tally.add(paused, "tv.app", 5 * day + 2 * HOUR)
        val usage = tally.finish(6 * day).getValue("tv.app")
        assertEquals(HOUR, usage.perDayMs[4])
        assertEquals(2 * HOUR, usage.perDayMs[5])
    }

    @Test
    fun theScreenTurningOffEndsTheSession() {
        val tally = Usage.Tally(starts, "couch")
        tally.add(resumed, "tv.app", day)
        tally.add(Usage.Tally.SCREEN_OFF, null, day + HOUR)
        // The pause Android logs later doesn't add the hours the TV spent off.
        tally.add(paused, "tv.app", day + 9 * HOUR)
        assertEquals(HOUR, tally.finish(2 * day).getValue("tv.app").weekMs)
    }

    @Test
    fun theAppInFrontStillCountsUntilNow() {
        val tally = Usage.Tally(starts, "couch")
        tally.add(resumed, "tv.app", 6 * day)
        assertEquals(30 * MINUTE, tally.finish(6 * day + 30 * MINUTE).getValue("tv.app").weekMs)
    }

    @Test
    fun anOpenIsAnAppComingToTheFrontAfterAnotherOne() {
        val tally = Usage.Tally(starts, "couch")
        // Two activities of the same app in a row are one open.
        tally.add(resumed, "tv.app", day)
        tally.add(paused, "tv.app", day + MINUTE)
        tally.add(resumed, "tv.app", day + MINUTE)
        tally.add(paused, "tv.app", day + 2 * MINUTE)
        tally.add(resumed, "couch", day + 2 * MINUTE)
        tally.add(paused, "couch", day + 3 * MINUTE)
        tally.add(resumed, "tv.app", day + 3 * MINUTE)
        tally.add(paused, "tv.app", day + 4 * MINUTE)
        val result = tally.finish(2 * day)
        assertEquals(2, result.getValue("tv.app").opens)
        assertEquals(3 * MINUTE, result.getValue("tv.app").weekMs)
        // The launcher itself is left out.
        assertFalse("couch" in result)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
