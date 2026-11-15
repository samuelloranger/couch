package cloud.samlo.couch

import cloud.samlo.couch.data.InputIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputIconTest {
    @Test
    fun guessesFromTheName() {
        assertEquals(InputIcon.PS5, InputIcon.resolve(null, "PS5"))
        assertEquals(InputIcon.SWITCH2, InputIcon.resolve(null, "Switch 2"))
        assertEquals(InputIcon.SWITCH, InputIcon.resolve(null, "Nintendo Switch"))
        assertEquals(InputIcon.GAME, InputIcon.resolve(null, "Console de jeu"))
        assertEquals(InputIcon.SERVER, InputIcon.resolve(null, "Serveur"))
    }

    @Test
    fun unknownNamesFallBackToHdmi() {
        assertEquals(InputIcon.HDMI, InputIcon.resolve(null, "HDMI 3"))
        assertEquals(InputIcon.HDMI, InputIcon.resolve(null, ""))
    }

    @Test
    fun theUsersPickBeatsTheGuess() {
        assertEquals(InputIcon.XBOX, InputIcon.resolve("xbox", "PS5"))
    }

    @Test
    fun autoOrUnknownIdsStillGuess() {
        assertEquals(InputIcon.PS4, InputIcon.resolve("auto", "ps4 pro"))
        assertEquals(InputIcon.PS4, InputIcon.resolve("gone-icon", "ps4 pro"))
    }

    @Test
    fun specificMarksWinOverGenericOnes() {
        assertEquals(InputIcon.STEAM_DECK, InputIcon.resolve(null, "Steam Deck"))
        assertEquals(InputIcon.PLAYSTATION, InputIcon.resolve(null, "PlayStation"))
    }

    @Test
    fun tunerNamesGuessTheAntenna() {
        assertEquals(InputIcon.ANTENNA, InputIcon.resolve(null, "Live TV"))
        assertEquals(InputIcon.ANTENNA, InputIcon.resolve(null, "Télé en direct"))
        assertEquals(InputIcon.ANTENNA, InputIcon.resolve(null, "Antenna"))
    }

    @Test
    fun idsAreUniqueAndLookUpBack() {
        assertEquals(InputIcon.entries.size, InputIcon.entries.map { it.id }.toSet().size)
        InputIcon.entries.forEach { assertEquals(it, InputIcon.of(it.id)) }
        assertEquals(InputIcon.AUTO, InputIcon.of(null))
    }

    @Test
    fun brandMarksHaveAColour() {
        assertTrue(InputIcon.entries.filter { it.res != 0 && it.color != null }.all { it.color!!.startsWith("#") })
    }
}
