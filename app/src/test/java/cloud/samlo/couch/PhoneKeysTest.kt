package cloud.samlo.couch

import cloud.samlo.couch.phone.Keys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneKeysTest {
    private val names = "UP DOWN LEFT RIGHT OK BACK HOME MENU PLAY_PAUSE REWIND FAST_FORWARD VOLUME_UP VOLUME_DOWN MUTE POWER INPUT SETTINGS CHANNEL_UP CHANNEL_DOWN"
        .split(' ')

    @Test
    fun everyKeyTheSpecNamesIsInTheTable() {
        names.forEach { assertNotNull(it, Keys.named(it)) }
        assertEquals(names.size, Keys.all.size)
        assertNull(Keys.named("up"))
        assertNull(Keys.named("SLEEP"))
    }

    @Test
    fun codesAreUniqueAndSane() {
        assertEquals(Keys.all.size, Keys.all.map { it.name }.toSet().size)
        assertEquals(Keys.all.size, Keys.all.map { it.android }.toSet().size)
        val linux = Keys.all.mapNotNull { it.linux }
        assertEquals(linux.size, linux.toSet().size)
        assertTrue(linux.all { it in 1..767 })
        // The D-pad and the keys Android always maps are emitted through the relay.
        listOf("UP", "DOWN", "LEFT", "RIGHT", "OK", "BACK", "HOME").forEach { assertNotNull(it, Keys.named(it)!!.linux) }
        assertEquals(19, Keys.named("UP")!!.android)
        assertEquals(23, Keys.named("OK")!!.android)
        assertEquals(103, Keys.named("UP")!!.linux)
    }

    @Test
    fun spacesBecomePercentSAndQuotesAreEscaped() {
        assertEquals("input text 'hello%sworld'", Keys.textCommand("hello world"))
        assertEquals("input text 'it'\\''s'", Keys.textCommand("it's"))
    }

    @Test
    fun shellMetacharactersStayInsideTheQuotes() {
        assertEquals("input text '\$(reboot);%sls%s|%scat%s>%sx'", Keys.textCommand("\$(reboot); ls | cat > x"))
        assertEquals("input text '`id`&&\"a\"\\'", Keys.textCommand("`id`&&\"a\"\\"))
    }

    @Test
    fun controlCharactersAreDroppedAndTheLengthIsCapped() {
        assertEquals("input text 'ab'", Keys.textCommand("a\nb\r\u0000"))
        assertNull(Keys.textCommand(""))
        assertNull(Keys.textCommand("\n\n"))
        val long = Keys.textCommand("x".repeat(900))!!
        assertEquals("input text '".length + Keys.MAX_TEXT + 1, long.length)
    }
}
