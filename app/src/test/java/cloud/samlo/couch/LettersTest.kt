package cloud.samlo.couch

import cloud.samlo.couch.index.IndexRail
import cloud.samlo.couch.index.Letters
import cloud.samlo.couch.index.SearchRoute
import cloud.samlo.couch.index.SearchRoute.At
import cloud.samlo.couch.index.SearchRoute.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LettersTest {
    @Test
    fun initialFoldsCaseAndAccents() {
        assertEquals('P', Letters.initial("plex"))
        assertEquals('E', Letters.initial("Écran"))
        assertEquals('#', Letters.initial("1Password"))
        assertEquals('#', Letters.initial("  "))
        assertEquals('#', Letters.initial("日本"))
    }

    @Test
    fun stepSkipsLettersWithoutApps() {
        val present = Letters.present(listOf("Arte", "Crunchyroll", "Plex", "9GAG"))
        assertEquals('C', Letters.step(present, 'A', 1))
        assertEquals('P', Letters.step(present, 'C', 1))
        assertEquals('#', Letters.step(present, 'P', 1))
        assertNull(Letters.step(present, '#', 1))
        assertEquals('C', Letters.step(present, 'P', -1))
        assertNull(Letters.step(present, 'A', -1))
    }

    @Test
    fun enteringTheStripLandsOnTheFirstLetterWithApps() {
        assertEquals('C', Letters.step(Letters.present(listOf("Crunchyroll", "Plex")), null, 1))
        assertNull(Letters.step(emptySet(), null, 1))
    }

    @Test
    fun downFromAnEmptyFieldEntersTheStripThenTheResults() {
        val field = At(Stop.FIELD)
        assertEquals(At(Stop.STRIP), SearchRoute.vertical(field, true, 4, hasQuery = false, letterChosen = false))
        assertEquals(At(Stop.ROW, 0), SearchRoute.vertical(At(Stop.STRIP), true, 4, hasQuery = false, letterChosen = true))
    }

    @Test
    fun aTypedQuerySkipsTheStrip() {
        assertEquals(At(Stop.ROW, 0), SearchRoute.vertical(At(Stop.FIELD), true, 2, hasQuery = true, letterChosen = false))
        assertEquals(At(Stop.ROW, 0), SearchRoute.vertical(At(Stop.MIC), true, 2, hasQuery = true, letterChosen = false))
    }

    @Test
    fun upFromTheFirstRowReturnsToTheStripOnlyForALetter() {
        assertEquals(At(Stop.STRIP), SearchRoute.vertical(At(Stop.ROW, 0), false, 3, hasQuery = false, letterChosen = true))
        assertEquals(At(Stop.FIELD), SearchRoute.vertical(At(Stop.ROW, 0), false, 3, hasQuery = true, letterChosen = false))
        assertEquals(At(Stop.FIELD), SearchRoute.vertical(At(Stop.STRIP), false, 3, hasQuery = false, letterChosen = true))
    }

    @Test
    fun rowsNeverWrap() {
        assertEquals(At(Stop.ROW, 2), SearchRoute.vertical(At(Stop.ROW, 2), true, 3, hasQuery = false, letterChosen = true))
        assertEquals(At(Stop.ROW, 1), SearchRoute.vertical(At(Stop.ROW, 2), false, 3, hasQuery = false, letterChosen = true))
        assertEquals(At(Stop.FIELD), SearchRoute.vertical(At(Stop.FIELD), false, 3, hasQuery = false, letterChosen = false))
    }

    @Test
    fun railWalkStopsAtBothEnds() {
        val items = listOf("search", "home", "inputs", "settings")
        assertEquals("inputs", IndexRail.Order.step(items, "home", true))
        assertEquals("search", IndexRail.Order.step(items, "home", false))
        assertNull(IndexRail.Order.step(items, "search", false))
        assertNull(IndexRail.Order.step(items, "settings", true))
        assertNull(IndexRail.Order.step(items, "missing", true))
    }
}
