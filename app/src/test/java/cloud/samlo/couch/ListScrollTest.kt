package cloud.samlo.couch

import cloud.samlo.couch.index.ListScroll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListScrollTest {
    // The Index shows 11 rows with the last one in the fade, so 10 are clear of it, and keeps 3 rows above the selection.
    private fun first(index: Int, count: Int) = ListScroll.first(index, count, rowsShown = 11, rowsAbove = 3)

    @Test
    fun aListThatFitsClearOfTheFadeNeverScrolls() {
        for (i in 0 until 10) assertEquals(0, first(i, 10))
    }

    @Test
    fun theSelectionKeepsRowsAboveItOnceTheListScrolls() {
        assertEquals(0, first(3, 30))
        assertEquals(1, first(4, 30))
        assertEquals(12, first(15, 30))
    }

    @Test
    fun theLastRowRisesAboveTheFadeInsteadOfStayingInIt() {
        // With 30 apps the last one sits in the tenth place, the last row clear of the fade.
        assertEquals(30 - 10, first(29, 30))
        // 11 apps: the 11th used to stand in the fade at the bottom; now the list scrolls one row.
        assertEquals(1, first(10, 11))
    }

    @Test
    fun fadesOnlyWhenARowIsLeftUnderIt() {
        assertFalse(ListScroll.fades(10, 11))
        assertTrue(ListScroll.fades(11, 11))
    }
}
