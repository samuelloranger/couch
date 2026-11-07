package cloud.samlo.couch

import cloud.samlo.couch.hass.HassJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HassJsonTest {
    private fun state(id: String, state: String, attributes: String = "{}") =
        JSONObject("""{"entity_id":"$id","state":"$state","attributes":$attributes}""")

    @Test
    fun dimmableLightOn() {
        val d = HassJson.device(state("light.desk", "on", """{"friendly_name":"Desk lamp","brightness":153,"supported_color_modes":["brightness"]}"""))!!
        assertEquals("Desk lamp", d.name)
        assertTrue(d.on)
        assertTrue(d.available)
        assertTrue(d.dimmable)
        assertEquals(60, d.brightness)
        assertTrue(d.isLight)
    }

    @Test
    fun lightOffHasNoBrightness() {
        val d = HassJson.device(state("light.desk", "off", """{"supported_color_modes":["color_temp"]}"""))!!
        assertFalse(d.on)
        assertTrue(d.dimmable)
        assertEquals(0, d.brightness)
        assertEquals("desk", d.name)
    }

    @Test
    fun onOffLightIsNotDimmable() {
        val d = HassJson.device(state("light.strip", "on", """{"supported_color_modes":["onoff"]}"""))!!
        assertFalse(d.dimmable)
    }

    @Test
    fun plugUnavailable() {
        val d = HassJson.device(state("switch.tree", "unavailable"))!!
        assertFalse(d.available)
        assertFalse(d.on)
        assertFalse(d.isLight)
        assertFalse(d.dimmable)
        assertEquals("switch", d.domain)
    }

    @Test
    fun otherDomainsAreSkipped() {
        assertNull(HassJson.device(state("sensor.temperature", "21")))
        assertNull(HassJson.device(state("media_player.tv", "on")))
    }

    @Test
    fun listKeepsLightsAndPlugsByName() {
        val list = HassJson.devices(
            """[{"entity_id":"switch.b","state":"off","attributes":{"friendly_name":"beta"}},
                {"entity_id":"sensor.x","state":"1","attributes":{}},
                {"entity_id":"light.a","state":"on","attributes":{"friendly_name":"Alpha"}}]""",
        )!!
        assertEquals(listOf("light.a", "switch.b"), list.map { it.id })
        assertNull(HassJson.devices("not json"))
    }

    @Test
    fun baseUrlIsNormalised() {
        assertEquals("http://10.0.0.2:8123", HassJson.baseUrl(" 10.0.0.2:8123/ "))
        assertEquals("https://ha.example.com", HassJson.baseUrl("https://ha.example.com/api/"))
        assertEquals("", HassJson.baseUrl("  "))
    }

    @Test
    fun shortNameIsCapped() {
        assertEquals("Salon", HassJson.shortName(" Salon "))
        assertEquals(HassJson.MAX_LABEL, HassJson.shortName("A very long light name indeed").length)
        assertEquals("Tree", HassJson.shortName("Tree (mss110) - Main channel"))
        assertEquals("Porch", HassJson.shortName("Porch - Switch 1"))
    }
}
