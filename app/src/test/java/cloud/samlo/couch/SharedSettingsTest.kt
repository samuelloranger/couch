package cloud.samlo.couch

import cloud.samlo.couch.data.Config
import cloud.samlo.couch.sync.Client
import cloud.samlo.couch.sync.Peers
import cloud.samlo.couch.sync.Server
import cloud.samlo.couch.sync.SharedSettings
import cloud.samlo.couch.sync.Snapshot
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

class SharedSettingsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val servers = ArrayList<Server>()

    @After
    fun stop() {
        servers.forEach(Server::stop)
        Config.onSaved = null
    }

    @Test
    fun aLoadedConfigSavesOnlyWhatItChanged() {
        val dir = folder.newFolder()
        Config().apply { accent = "teal" }.save(dir)
        val page = Config.load(dir)
        // Another writer (a phone, another TV) changes the weather city meanwhile.
        Config.load(dir).apply { weatherCity = "Montréal" }.save(dir)
        var heard: Set<String>? = null
        Config.onSaved = { heard = it }
        page.clock24h = false
        page.save(dir)
        val now = Config.load(dir)
        assertEquals("Montréal", now.weatherCity)
        assertFalse(now.clock24h)
        assertEquals("teal", now.accent)
        assertEquals(setOf("clock24h"), heard)
    }

    @Test
    fun aFirstSaveReportsOnlyWhatDiffersFromTheDefaults() {
        val dir = folder.newFolder()
        var heard: Set<String>? = null
        Config.onSaved = { heard = it }
        Config().apply { fahrenheit = true }.save(dir)
        assertEquals(setOf("fahrenheit"), heard)
    }

    @Test
    fun aQuietSaveIsNotReported() {
        val dir = folder.newFolder()
        var heard = false
        Config.onSaved = { heard = true }
        Config().apply { fahrenheit = true }.save(dir, quiet = true)
        assertFalse(heard)
        assertTrue(Config.load(dir).fahrenheit)
    }

    @Test
    fun theNewestChangeWinsAndTiesSettleTheSameWayOnBothSides() {
        assertTrue(SharedSettings.wins(2, "a", 1, "b"))
        assertFalse(SharedSettings.wins(1, "b", 2, "a"))
        // Same time: each side picks the larger id, so both end on "b".
        assertTrue(SharedSettings.wins(5, "b", 5, "a"))
        assertFalse(SharedSettings.wins(5, "a", 5, "b"))
    }

    @Test
    fun onlyChangedSharedSettingsTravel() {
        val store = SharedSettings(null)
        assertFalse(store.changed(setOf("channels", "godmode"), 10))
        assertTrue(store.changed(setOf("accent", "hud"), 10))
        val out = store.outgoing(JSONObject().put("accent", "teal").put("hud", "ms").put("weatherCity", "X"))
        assertEquals(setOf("accent"), out.keys().asSequence().toSet())
        assertEquals("teal", out.getJSONObject("accent").getString("v"))
        assertEquals(10L, out.getJSONObject("accent").getLong("at"))
    }

    @Test
    fun anIncomingSettingWinsOnlyWhenNewerAndShared() {
        val store = SharedSettings(null)
        store.changed(setOf("accent"), 10)
        val incoming = JSONObject()
            .put("accent", JSONObject().put("v", "old").put("at", 5))
            .put("weatherCity", JSONObject().put("v", "Québec").put("at", 7))
            .put("channels", JSONObject().put("v", JSONObject()).put("at", 99))
            .put("fahrenheit", JSONObject().put("v", true).put("at", 0))
        val here = JSONObject().put("weatherCity", "Montréal")
        val (patch, at) = store.winners("me", "them", incoming, here)
        assertEquals(setOf("weatherCity"), patch.keys().asSequence().toSet())
        assertEquals(mapOf("weatherCity" to 7L), at)
        // Not recorded until taken: the same answer wins again.
        assertEquals(1, store.winners("me", "them", incoming, here).first.length())
        store.took(at)
        // Taken, and a tie with the same value applied: nothing more to do, even for the larger id.
        val (again, againAt) = store.winners("me", "them", incoming, here.put("weatherCity", "Québec"))
        assertEquals(0, again.length())
        assertEquals(mapOf("weatherCity" to 7L), againAt)
    }

    @Test
    fun stampsSurviveARestart() {
        val file = folder.newFile()
        SharedSettings(file).changed(setOf("accent"), 42)
        assertEquals(42L, SharedSettings(file).outgoing(JSONObject().put("accent", "teal")).getJSONObject("accent").getLong("at"))
    }

    @Test
    fun twoPairedTvsSwapSettingsBothWays() {
        val a = Peers.load(null, "A")
        val b = Peers.load(null, "B")
        val server = Server(b) { Snapshot(b.id, b.name, 1L, LocalDate.now(), emptyMap()) }.also { servers += it }
        val port = server.start()
        server.onPair = { _, _ -> true }
        assertTrue(Client.pair("127.0.0.1", port, a) { _, _ -> true } is Client.Pairing.Paired)
        server.onPair = null
        var gotOnB: JSONObject? = null
        server.onSettings = { _, theirs ->
            gotOnB = theirs
            JSONObject().put("weatherCity", JSONObject().put("v", "Québec").put("at", 3))
        }
        val mine = JSONObject().put("accent", JSONObject().put("v", "teal").put("at", 2))
        val reply = Client.settings("127.0.0.1", port, a, a.get(b.id)!!, mine)
        assertEquals(mine.toString(), gotOnB.toString())
        assertEquals("Québec", reply!!.getJSONObject("weatherCity").getString("v"))
        // Off: nothing is swapped.
        server.shares = { false }
        assertNull(Client.settings("127.0.0.1", port, a, a.get(b.id)!!, mine))
    }
}
