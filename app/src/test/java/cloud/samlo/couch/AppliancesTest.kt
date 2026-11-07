package cloud.samlo.couch

import cloud.samlo.couch.hass.Appliances
import cloud.samlo.couch.hass.Appliances.Phase
import cloud.samlo.couch.hass.HassDevice
import cloud.samlo.couch.hass.HassJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppliancesTest {
    @Test
    fun stateSensorsAreRecognisedByTheirEnding() {
        assertTrue(Appliances.isStateSensor("sensor.dryer_machine_state"))
        assertTrue(Appliances.isStateSensor("sensor.dishwasher_current_status"))
        assertFalse(Appliances.isStateSensor("sensor.dryer_energy"))
        assertFalse(Appliances.isStateSensor("binary_sensor.dryer_machine_state"))
        assertTrue(HassDevice.supported("sensor.dryer_machine_state"))
        assertFalse(HassDevice.supported("sensor.outside_temperature"))
    }

    @Test
    fun theMachineKeepsItsOwnName() {
        assertEquals("Dryer", Appliances.deviceName("sensor.dryer_machine_state", "Dryer Machine state"))
        assertEquals("Kitchen", Appliances.deviceName("sensor.dw_current_status", "Kitchen current status"))
        assertEquals("Other name", Appliances.deviceName("sensor.dw_current_status", "Other name"))
    }

    @Test
    fun finishSensorsShareTheDevicePrefix() {
        assertEquals(
            listOf("sensor.dw_remaining_time", "sensor.dw_estimated_finish", "sensor.dw_finish_time", "sensor.dw_time_remaining", "sensor.dw_end_time"),
            Appliances.finishCandidates("sensor.dw_current_status"),
        )
        assertTrue(Appliances.finishCandidates("sensor.other").isEmpty())
    }

    @Test
    fun phasesCoverCommonStates() {
        assertEquals(Phase.RUNNING, Appliances.phase("run"))
        assertEquals(Phase.RUNNING, Appliances.phase("Drying"))
        assertEquals(Phase.PAUSED, Appliances.phase("pause"))
        assertEquals(Phase.DELAYED, Appliances.phase("Delayed start"))
        assertEquals(Phase.DONE, Appliances.phase("end"))
        assertEquals(Phase.IDLE, Appliances.phase("idle"))
        assertEquals(Phase.OTHER, Appliances.phase("rinse_aid_low"))
        assertEquals("Rinse aid low", Appliances.readable("rinse_aid_low"))
    }

    @Test
    fun finishFromATimeOrMinutesLeft() {
        val now = 1_000_000_000_000L
        assertEquals(now + 23 * 60_000L, Appliances.finishAt("23", "min", now))
        val at = Appliances.finishAt("2001-09-09T01:56:40+00:00", "", now)
        assertEquals(now + 600_000L, at)
        assertNull(Appliances.finishAt("unknown", "", now))
        assertNull(Appliances.finishAt("2001-01-01T00:00:00+00:00", "", now))
    }

    @Test
    fun aRunningApplianceIsOnButNotSwitchable() {
        val d = HassJson.device(JSONObject("""{"entity_id":"sensor.dryer_machine_state","state":"run","attributes":{"friendly_name":"Sécheuse"}}"""))!!
        assertTrue(d.on)
        assertTrue(d.isAppliance)
        assertFalse(d.switchable)
        assertEquals("run", d.raw)
        assertEquals(2, HassDevice.order(d.id))
    }
}
