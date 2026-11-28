package cloud.samlo.couch

import cloud.samlo.couch.ui.Focus
import cloud.samlo.couch.ui.Tokens
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

    @Test
    fun idleGraphicsReachThreeToOneOnTheBackground() {
        assertTrue(contrast(Tokens.GRAPHIC_IDLE_HEX, "#0A0D0C") >= 3.0)
    }

    private fun luminance(hex: String): Double {
        val c = (0..2).map { Integer.parseInt(hex.substring(1 + it * 2, 3 + it * 2), 16) / 255.0 }
            .map { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
    }

    private fun contrast(a: String, b: String): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }
}
