package cloud.samlo.couch

import cloud.samlo.couch.index.HomeRowPlacement
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRowPlacementTest {
    @Test
    fun staysAtTheColumnWhenTheWholeRowFitsBeforeTheCluster() {
        assertEquals(400, HomeRowPlacement.startX(width = 280, columnX = 400, listX = 84, limit = 700))
        assertEquals(400, HomeRowPlacement.startX(width = 300, columnX = 400, listX = 84, limit = 700))
    }

    @Test
    fun startsOverTheListWhenItWouldReachTheCluster() {
        assertEquals(84, HomeRowPlacement.startX(width = 301, columnX = 400, listX = 84, limit = 700))
    }
}
