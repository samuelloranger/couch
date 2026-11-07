package cloud.samlo.couch

import cloud.samlo.couch.remote.PanelView
import org.junit.Assert.assertEquals
import org.junit.Test

class PanelGridTest {
    @Test
    fun sectionsAreRunsInOrder() {
        assertEquals(listOf(0 until 2, 2 until 3, 3 until 6), PanelView.sections(listOf("h", "h", "i", "a", "a", "a")))
        assertEquals(emptyList<IntRange>(), PanelView.sections(emptyList()))
    }

    @Test
    fun fewestColumnsThatFit() {
        // 486dp: a 540dp screen less its overscan. Six devices, four inputs, thirteen apps fit in four.
        assertEquals(4, PanelView.columnsFor(listOf(6, 4, 13), 486))
        assertEquals(3, PanelView.columnsFor(listOf(2, 6), 486))
        // Too many for any width: the most columns, and the panel scrolls.
        assertEquals(PanelView.MAX_COLUMNS, PanelView.columnsFor(listOf(200), 486))
    }
}
