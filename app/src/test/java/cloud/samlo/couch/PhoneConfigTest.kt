package cloud.samlo.couch

import cloud.samlo.couch.data.Config
import cloud.samlo.couch.phone.ConfigPatch
import cloud.samlo.couch.phone.SchemaModel
import cloud.samlo.couch.sync.Crypto
import cloud.samlo.couch.sync.Peer
import cloud.samlo.couch.sync.Peers
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhoneConfigTest {
    @Test
    fun aPatchChangesOnlyItsKeys() {
        val base = Config().apply { accent = "#FF8A4C"; hiddenApps = setOf("a.b"); names = mapOf("x" to "Y") }
        val next = ConfigPatch.apply(base, JSONObject("""{"clock24h":false,"ambientMinutes":10,"pinnedApps":["p.q"]}"""))
        assertFalse(next.clock24h)
        assertEquals(10, next.ambientMinutes)
        assertEquals(listOf("p.q"), next.pinnedApps)
        assertEquals("#FF8A4C", next.accent)
        assertEquals(setOf("a.b"), next.hiddenApps)
        assertEquals(mapOf("x" to "Y"), next.names)
    }

    @Test
    fun unknownKeysAreIgnoredAndValuesAreChecked() {
        val next = ConfigPatch.apply(Config(), JSONObject("""{"nonsense":1,"appSort":"sideways","ambientMinutes":-4,"wallpaperHours":0,"subreddit":"r/EarthPorn"}"""))
        assertEquals("usage", next.appSort)
        assertEquals(0, next.ambientMinutes)
        assertEquals(1, next.wallpaperHours)
        assertEquals("EarthPorn", next.subreddit)
        assertEquals(Config.DEFAULT_SUBREDDIT, ConfigPatch.apply(Config(), JSONObject("""{"subreddit":" "}""")).subreddit)
    }

    @Test(expected = JSONException::class)
    fun aValueOfTheWrongTypeIsRejected() {
        ConfigPatch.apply(Config(), JSONObject("""{"pinnedApps":[{"not":"a string"}]}"""))
    }

    @Test
    fun theSchemaCarriesOptionsAndConditionsInTheSpecShape() {
        val json = SchemaModel.toJson(
            listOf(
                SchemaModel.Group(
                    "look", "Look",
                    listOf(
                        SchemaModel.Row("accent", "Accent", "help", "swatches", listOf(SchemaModel.Option("#34D399", "Mint", "#34D399"))),
                        SchemaModel.Row("clock24h", "24h", "", "toggle"),
                        SchemaModel.Row("subreddit", "Sub", "", "text", visibleWhen = SchemaModel.When("wallpaper", "reddit")),
                        SchemaModel.Row("ambientMinutes", "Ambient", "", "choice", listOf(SchemaModel.Option(0, "Off"), SchemaModel.Option(5, "5 min"))),
                    ),
                ),
            ),
        )
        assertEquals("settings", json.getString("t"))
        val rows = json.getJSONArray("groups").getJSONObject(0).getJSONArray("rows")
        assertEquals("#34D399", rows.getJSONObject(0).getJSONArray("options").getJSONObject(0).getString("color"))
        assertFalse(rows.getJSONObject(1).has("help"))
        assertFalse(rows.getJSONObject(1).has("options"))
        assertEquals("reddit", rows.getJSONObject(2).getJSONObject("visibleWhen").getString("equals"))
        assertEquals(5, rows.getJSONObject(3).getJSONArray("options").getJSONObject(1).getInt("value"))
    }

    @Test
    fun aPeersFileKeepsKindsAndOldFilesLoadAsTvs() {
        val file = File.createTempFile("sync", ".json").apply { deleteOnExit() }
        val peers = Peers.load(file, "tv")
        peers.add(Peer("p1", "Phone", Crypto.random(32), kind = Peer.PHONE))
        peers.add(Peer("t1", "TV", Crypto.random(32)))
        val again = Peers.load(file, "tv")
        assertEquals(listOf("p1"), again.phones().map { it.id })
        assertEquals(listOf("t1"), again.tvs().map { it.id })
        assertTrue(again.get("p1")!!.isPhone)
        // A file written before phones existed has no kind.
        file.writeText(file.readText().replace(",\"kind\":\"tv\"", "").replace(",\"kind\":\"phone\"", ""))
        val old = Peers.load(file, "tv")
        assertEquals(2, old.tvs().size)
        assertTrue(old.phones().isEmpty())
    }

    @Test
    fun aStringForABooleanIsRejectedNotIgnored() {
        for (patch in listOf("""{"clock24h":"nope"}""", """{"ambientMinutes":"5"}""", """{"accent":3}""", """{"hiddenApps":"x"}""")) {
            try {
                ConfigPatch.apply(Config(), JSONObject(patch))
                org.junit.Assert.fail("accepted $patch")
            } catch (e: JSONException) {
                assertTrue(e.message!!.isNotEmpty())
            }
        }
    }

    @Test
    fun heldKeysAreDrainedOnce() {
        val held = cloud.samlo.couch.phone.HeldKeys()
        held.down(103)
        held.down(105)
        held.up(105)
        held.down(103)
        assertEquals(listOf(103), held.drain())
        assertTrue(held.drain().isEmpty())
    }

    @Test
    fun aPhoneKeepsAtMostTwoSessions() {
        assertTrue(cloud.samlo.couch.phone.Phones.oldestToEvict(emptyList<Int>(), 2).isEmpty())
        assertTrue(cloud.samlo.couch.phone.Phones.oldestToEvict(listOf(1), 2).isEmpty())
        assertEquals(listOf(1), cloud.samlo.couch.phone.Phones.oldestToEvict(listOf(1, 2), 2))
        assertEquals(listOf(1, 2), cloud.samlo.couch.phone.Phones.oldestToEvict(listOf(1, 2, 3), 2))
    }
}
