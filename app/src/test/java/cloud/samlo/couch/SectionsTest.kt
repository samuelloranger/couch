package cloud.samlo.couch

import cloud.samlo.couch.data.AppUsage
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.Sections
import org.junit.Assert.assertEquals
import org.junit.Test

class SectionsTest {
    private fun order(saved: List<String>, ids: List<String>) =
        Sections.order(ids, Config().apply { sectionOrder = saved })

    @Test
    fun noSavedOrderKeepsTheDefault() {
        assertEquals(listOf("apps", "inputs"), order(emptyList(), listOf("apps", "inputs")))
    }

    @Test
    fun savedOrderWins() {
        assertEquals(listOf("inputs", "apps"), order(listOf("inputs", "apps"), listOf("apps", "inputs")))
    }

    @Test
    fun newRowsGoLastInDefaultOrder() {
        assertEquals(
            listOf("inputs", "apps", "ch:1", "ch:2"),
            order(listOf("inputs", "apps"), listOf("apps", "inputs", "ch:1", "ch:2")),
        )
    }

    @Test
    fun staleIdsAreDropped() {
        assertEquals(listOf("apps"), order(listOf("ch:9", "apps"), listOf("apps")))
    }

    @Test
    fun recentFollowsAppsUntilPlaced() {
        assertEquals(
            listOf("inputs", "apps", "recent", "ch:1"),
            order(listOf("inputs", "apps"), listOf("apps", "recent", "inputs", "ch:1")),
        )
    }

    @Test
    fun placedRecentKeepsItsPlace() {
        assertEquals(listOf("recent", "inputs", "apps"), order(listOf("recent", "inputs", "apps"), listOf("apps", "recent", "inputs")))
    }

    @Test
    fun recentIsLastUsedFirstWithoutUnusedApps() {
        val usage = mapOf("a" to used(10), "b" to used(30), "c" to used(0), "d" to used(20))
        assertEquals(listOf("b", "d", "a"), Sections.recent(listOf("a", "b", "c", "d", "e"), usage))
    }

    @Test
    fun recentIsCappedAndEmptyWithoutUsageAccess() {
        val usage = (1..12).associate { "app$it" to used(it.toLong()) }
        assertEquals(Sections.RECENT_COUNT, Sections.recent(usage.keys.toList(), usage).size)
        assertEquals(emptyList<String>(), Sections.recent(listOf("a"), null))
    }

    private fun used(at: Long) = AppUsage(LongArray(7), 1, at)

    @Test
    fun channelIdsAreNamespaced() {
        assertEquals("ch:42", Sections.channelId(42))
    }
}
