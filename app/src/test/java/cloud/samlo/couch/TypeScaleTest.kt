package cloud.samlo.couch

import cloud.samlo.couch.ui.Focus
import cloud.samlo.couch.ui.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypeScaleTest {
    @Test
    fun rolesNeverDropBelowTheFloorAndGrow() {
        val roles = listOf(Type.CAPTION, Type.LABEL, Type.BODY, Type.HEADING, Type.TITLE, Type.DISPLAY)
        assertTrue(roles.all { it >= 12f })
        assertEquals(roles.sorted(), roles)
        assertEquals(roles.size, roles.toSet().size)
    }

    @Test
    fun wideCardsGrowLessThanSmallTiles() {
        assertEquals(Focus.SCALE_WIDE, Focus.scale(164), 0f)
        assertEquals(Focus.SCALE_WIDE, Focus.scale(Focus.WIDE_DP), 0f)
        assertEquals(Focus.SCALE_SMALL, Focus.scale(Focus.WIDE_DP - 1), 0f)
        assertEquals(Focus.SCALE_SMALL, Focus.scale(56), 0f)
    }
}
