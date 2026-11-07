package cloud.samlo.couch

import cloud.samlo.couch.godmode.IdleClose
import org.junit.Assert.assertEquals
import org.junit.Test

class IdleCloseTest {
    private val now = 10 * HOUR
    private val apps = listOf("tv.recent", "tv.old", "tv.never", "couch")

    @Test
    fun closesOnlyWhatSatIdlePastTheLimit() {
        val used = mapOf("tv.recent" to now - 20 * MINUTE, "tv.old" to now - 2 * HOUR)
        assertEquals(listOf("tv.never", "tv.old"), IdleClose.idle(apps, used, now, 60, "couch"))
    }

    @Test
    fun neverClosesCouchItself() {
        assertEquals(listOf("tv.never", "tv.old", "tv.recent"), IdleClose.idle(apps, emptyMap(), now, 60, "couch"))
    }

    @Test
    fun anAppUsedExactlyAtTheLimitStays() {
        assertEquals(listOf("tv.never"), IdleClose.idle(listOf("tv.edge", "tv.never"), mapOf("tv.edge" to now - HOUR), now, 60, "couch"))
    }

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
