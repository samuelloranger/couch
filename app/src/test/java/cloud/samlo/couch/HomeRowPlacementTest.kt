package cloud.samlo.couch

import cloud.samlo.couch.index.HomeRowPlacement
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRowPlacementTest {
    @Test
    fun staysAtTheColumnWhenTheNarrowestRowFitsBeforeTheCluster() {
        assertEquals(400, HomeRowPlacement.startX(minWidth = 280, columnX = 400, listX = 84, limit = 700))
        assertEquals(400, HomeRowPlacement.startX(minWidth = 300, columnX = 400, listX = 84, limit = 700))
    }

    @Test
    fun startsOverTheListWhenItWouldReachTheCluster() {
        assertEquals(84, HomeRowPlacement.startX(minWidth = 301, columnX = 400, listX = 84, limit = 700))
    }
}
