package cloud.samlo.couch

import cloud.samlo.couch.index.SearchMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchMatchTest {
    @Test
    fun caseDoesNotCount() {
        assertEquals(0, SearchMatch.rank("Netflix", "ne"))
        assertEquals(0, SearchMatch.rank("Netflix", "NET"))
        assertEquals(1, SearchMatch.rank("Netflix", "FLIX"))
    }

    @Test
    fun accentsFoldBothWays() {
        assertEquals(0, SearchMatch.rank("Café", "cafe"))
        assertEquals(0, SearchMatch.rank("Cafe", "café"))
        assertEquals(0, SearchMatch.rank("Télé Québec", "tele"))
        // An accented query matches an accented label inside a word, not at its start.
        assertEquals(1, SearchMatch.rank("Café", "é"))
    }

    @Test
    fun aMatchAtTheStartOfAWordRanksAboveOneInsideIt() {
        assertEquals(0, SearchMatch.rank("New Girl", "ne"))
        assertEquals(1, SearchMatch.rank("Disney+", "ne"))
        assertEquals(0, SearchMatch.rank("Prime Video", "video"))
        assertEquals(1, SearchMatch.rank("Apple TV", "ple"))
    }

    @Test
    fun noMatchOrAnEmptyQueryFindsNothing() {
        assertNull(SearchMatch.rank("Netflix", "zz"))
        assertNull(SearchMatch.rank("Netflix", ""))
        assertNull(SearchMatch.rank("", "ne"))
    }

    @Test
    fun theMatchPointsAtTheLabelsOwnCharactersForHighlighting() {
        val match = SearchMatch.find("Café", "fe")!!
        assertEquals(2, match.start)
        assertEquals(4, match.end)
        // Folded text is longer than the label here: the end still lands on the label's length.
        val accented = SearchMatch.find("Café", "café")!!
        assertEquals(0, accented.start)
        assertEquals(4, accented.end)
    }

    @Test
    fun aCombiningMarkInTheLabelShiftsTheHighlightsOffsets() {
        // A label with a combining mark (a decomposed accent, as a paste can carry) folds to a
        // shorter text, so a folded offset points at a different character than the label's own.
        val ne = SearchMatch.find("Cafe\u0301 Ne", "ne")!!
        assertEquals(6, ne.start)
        assertEquals(8, ne.end)
        assertEquals("Ne", "Cafe\u0301 Ne".substring(ne.start, ne.end))
        // The mark itself folds away: the match covers the letter it sits on, not the mark.
        val e = SearchMatch.find("Cafe\u0301", "é")!!
        assertEquals(3, e.start)
        assertEquals(4, e.end)
    }
}
