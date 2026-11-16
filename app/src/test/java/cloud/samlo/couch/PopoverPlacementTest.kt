package cloud.samlo.couch

import cloud.samlo.couch.ui.PopoverPlacement
import cloud.samlo.couch.ui.PopoverPlacement.Edge
import cloud.samlo.couch.ui.Side
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
        assertEquals(Edge.LEFT, spot.edge)
        assertEquals(150, spot.arrowY)
    }

    @Test
    fun turnsToTheLeftWhenTheRightEdgeHasNoRoom() {
        val spot = place(anchorLeft = 800, anchorTop = 200, anchorW = 100, anchorH = 32)
        assertTrue(spot.left)
        assertEquals(594, spot.panelLeft)
        assertEquals(Edge.RIGHT, spot.edge)
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

    @Test
    fun aFullWidthAnchorLeavesThePanelAtTheMarginInsteadOfOffScreen() {
        val spot = place(anchorLeft = 48, anchorTop = 200, anchorW = 860, anchorH = 52)
        assertTrue(spot.left)
        assertEquals(20, spot.panelLeft)
    }

    @Test
    fun opensUnderTheAnchorWithTheArrowOnItsTopEdge() {
        val spot = place(anchorLeft = 100, anchorTop = 200, anchorW = 200, anchorH = 32, panelH = 120, side = Side.BELOW)
        assertEquals(100, spot.panelLeft)
        assertEquals(242, spot.panelTop)
        assertEquals(Edge.TOP, spot.edge)
        // The arrow points at the anchor's middle, which is 100dp into the panel.
        assertEquals(100, spot.arrowX)
    }

    @Test
    fun aBelowPanelPullsBackInsideTheRightMarginAndTakesTheArrowWithIt() {
        val spot = place(anchorLeft = 900, anchorTop = 100, anchorW = 100, anchorH = 30, panelH = 120, side = Side.BELOW)
        assertEquals(744, spot.panelLeft)
        assertEquals(140, spot.panelTop)
        // The anchor's middle is past the panel's right corner: the arrow stops short of it.
        assertEquals(188, spot.arrowX)
    }

    @Test
    fun aBelowPanelFlipsAboveTheAnchorNearTheBottom() {
        val spot = place(anchorLeft = 100, anchorTop = 400, anchorW = 200, anchorH = 40, panelH = 120, side = Side.BELOW)
        assertEquals(270, spot.panelTop)
        assertEquals(Edge.BOTTOM, spot.edge)
    }

    @Test
    fun aBelowPanelUnderAWideAnchorPointsAtItsContentNotItsMiddle() {
        // The channel chip runs the whole column but its text sits at its left: the arrow follows
        // the text, instead of sliding to the panel's far corner at the anchor's middle.
        val spot = place(
            anchorLeft = 48, anchorTop = 200, anchorW = 860, anchorH = 32, panelH = 120, side = Side.BELOW,
            anchorX = 93,
        )
        assertEquals(48, spot.panelLeft)
        assertEquals(Edge.TOP, spot.edge)
        assertEquals(45, spot.arrowX)
    }

    @Test
    fun aFlippedPanelUnderAWideAnchorAlsoPointsAtItsContent() {
        val spot = place(
            anchorLeft = 48, anchorTop = 490, anchorW = 860, anchorH = 40, panelH = 120, side = Side.BELOW,
            anchorX = 93,
        )
        assertEquals(360, spot.panelTop)
        assertEquals(Edge.BOTTOM, spot.edge)
        assertEquals(45, spot.arrowX)
    }

    private fun place(
        anchorLeft: Int,
        anchorTop: Int,
        anchorW: Int,
        anchorH: Int,
        panelH: Int = 300,
        side: Side = Side.BESIDE,
        anchorX: Int = anchorLeft + anchorW / 2,
    ) = PopoverPlacement.place(
        anchorLeft = anchorLeft,
        anchorTop = anchorTop,
        anchorW = anchorW,
        anchorH = anchorH,
        screenW = 960,
        screenH = 540,
        panelW = 196,
        panelH = panelH,
        gap = 10,
        margin = 20,
        arrowInset = 8,
        side = side,
        anchorX = anchorX,
    )
}
