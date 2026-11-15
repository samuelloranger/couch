package cloud.samlo.couch

import cloud.samlo.couch.ui.PopoverPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopoverPlacementTest {
    @Test
    fun sitsRightOfTheAnchorLevelWithItsMiddle() {
        val spot = place(anchorLeft = 100, anchorTop = 200, anchorW = 200, anchorH = 32)
        assertFalse(spot.left)
        assertEquals(310, spot.panelLeft)
        assertEquals(66, spot.panelTop)
        assertEquals(150, spot.arrowY)
    }

    @Test
    fun turnsToTheLeftWhenTheRightEdgeHasNoRoom() {
        val spot = place(anchorLeft = 800, anchorTop = 200, anchorW = 100, anchorH = 32)
        assertTrue(spot.left)
        assertEquals(594, spot.panelLeft)
    }

    @Test
    fun staysInsideTheBottomMarginAndSlidesTheArrowDown() {
        val spot = place(anchorLeft = 100, anchorTop = 480, anchorW = 200, anchorH = 40)
        assertEquals(220, spot.panelTop)
        assertEquals(280, spot.arrowY)
    }

    @Test
    fun staysInsideTheTopMarginAndSlidesTheArrowUp() {
        val spot = place(anchorLeft = 100, anchorTop = 0, anchorW = 200, anchorH = 40)
        assertEquals(20, spot.panelTop)
        assertEquals(8, spot.arrowY)
    }

    private fun place(anchorLeft: Int, anchorTop: Int, anchorW: Int, anchorH: Int) = PopoverPlacement.place(
        anchorLeft = anchorLeft,
        anchorTop = anchorTop,
        anchorW = anchorW,
        anchorH = anchorH,
        screenW = 960,
        screenH = 540,
        panelW = 196,
        panelH = 300,
        gap = 10,
        margin = 20,
        arrowInset = 8,
    )
}
