package cloud.samlo.couch

import cloud.samlo.couch.bravia.TvSettings
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.remote.QuickItems
import cloud.samlo.couch.remote.RelayProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteButtonsTest {
    @Test
    fun relayLinesParse() {
        assertEquals(RelayProtocol.Event.Key(563, true), RelayProtocol.parse("key 563 1"))
        assertEquals(RelayProtocol.Event.Key(195, false), RelayProtocol.parse("key 195 0\n"))
        assertEquals(RelayProtocol.Event.Seen(158), RelayProtocol.parse("seen 158"))
        assertNull(RelayProtocol.parse("key nope 1"))
        assertNull(RelayProtocol.parse("hello"))
        assertNull(RelayProtocol.parse(""))
    }

    @Test
    fun keysLineSkipsUnsetButtons() {
        assertEquals("keys 563,195", RelayProtocol.keys(listOf(563, 195)))
        assertEquals("keys 563", RelayProtocol.keys(listOf(563, 0, 563)))
        // Nothing to hold back: the relay grabs nothing until a button is set up.
        assertEquals("keys -", RelayProtocol.keys(listOf(0, 0)))
    }

    @Test
    fun startCommandRenamesTheBinaryIntoPlaceAndReportsIt() {
        val command = RelayProtocol.start("/data/app/x/lib/arm", 4242, "abc")
        assertTrue(command.startsWith("pkill -x couch-relay;"))
        assertTrue("mv -f /data/local/tmp/couch-relay.new /data/local/tmp/couch-relay" in command)
        assertTrue(command.endsWith("/data/local/tmp/couch-relay 4242 abc && echo ${RelayProtocol.STARTED}"))
    }

    @Test
    fun navigationKeysAreNeverOfferedAsButtons() {
        listOf(103, 108, 105, 106, 352, 158, 115, 114, 113, 102).forEach { assertTrue(it in RelayProtocol.RESERVED) }
        assertFalse(563 in RelayProtocol.RESERVED)
    }

    @Test
    fun sleepTimerStepsBothWaysAndWraps() {
        assertEquals(15, QuickItems.nextSleep(0))
        assertEquals(30, QuickItems.nextSleep(15))
        assertEquals(0, QuickItems.nextSleep(120))
        assertEquals(120, QuickItems.nextSleep(0, -1))
        assertEquals(45, QuickItems.nextSleep(60, -1))
        // A timer set elsewhere to a minute count between steps moves from the step below it.
        assertEquals(30, QuickItems.nextSleep(20))
    }

    @Test
    fun tvChoicesCycleAndNumbersStepByFiveWithinRange() {
        val mode = TvSettings.Setting("pictureMode", "cinema", listOf("standard", "cinema", "game"))
        assertEquals("game", mode.next(1))
        assertEquals("standard", mode.next(-1))
        assertEquals("standard", TvSettings.Setting("pictureMode", "game", mode.choices).next(1))
        val brightness = TvSettings.Setting("brightness", "45", emptyList(), 0, 50)
        assertEquals("50", brightness.next(1))
        assertEquals("40", brightness.next(-1))
        // Already at the top: nothing to write.
        assertNull(TvSettings.Setting("brightness", "50", emptyList(), 0, 50).next(1))
    }

    @Test
    fun remoteButtonsAreOffByDefaultAndRoundTrip() {
        val fresh = Config()
        assertFalse(fresh.remoteButtons)
        assertEquals(0, fresh.remoteInputKey)
        val config = Config().apply {
            remoteButtons = true
            remoteInputKey = 563
            remoteSettingsKey = 195
        }
        val back = Config.fromJson(config.toJson())
        assertTrue(back.remoteButtons)
        assertEquals(563, back.remoteInputKey)
        assertEquals(195, back.remoteSettingsKey)
        assertEquals(0, Config.fromJson("""{"remoteInputKey": -4}""").remoteInputKey)
    }
}
