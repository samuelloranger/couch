package cloud.samlo.couch

import cloud.samlo.couch.hass.EntityStates
import cloud.samlo.couch.hass.HassSocket
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HassSocketTest {
    @Test
    fun acceptKeyMatchesTheRfcExample() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", HassSocket.acceptFor("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test
    fun shortFrameIsMasked() {
        val mask = byteArrayOf(0x37, 0xfa.toByte(), 0x21, 0x3d)
        // RFC 6455's masked "Hello".
        val expected = byteArrayOf(0x81.toByte(), 0x85.toByte(), 0x37, 0xfa.toByte(), 0x21, 0x3d, 0x7f, 0x9f.toByte(), 0x4d, 0x51, 0x58)
        assertArrayEquals(expected, HassSocket.encodeFrame(0x1, "Hello".toByteArray(), mask))
    }

    @Test
    fun longFramesCarryTheirLength() {
        val mask = ByteArray(4)
        val medium = HassSocket.encodeFrame(0x1, ByteArray(300), mask)
        assertEquals(0x80 or 126, medium[1].toInt() and 0xFF)
        assertEquals(300, ((medium[2].toInt() and 0xFF) shl 8) or (medium[3].toInt() and 0xFF))
        assertEquals(2 + 2 + 4 + 300, medium.size)
        val large = HassSocket.encodeFrame(0x1, ByteArray(70_000), mask)
        assertEquals(0x80 or 127, large[1].toInt() and 0xFF)
        assertEquals(2 + 8 + 4 + 70_000, large.size)
    }

    @Test
    fun changesMergeIntoTheLastState() {
        val states = EntityStates()
        val first = states.apply(JSONObject("""{"a":{"light.desk":{"s":"on","a":{"friendly_name":"Desk","brightness":255,"supported_color_modes":["brightness"]}}}}"""))
        assertEquals(100, first.single().brightness)

        val dimmed = states.apply(JSONObject("""{"c":{"light.desk":{"+":{"a":{"brightness":128}}}}}"""))
        assertEquals(50, dimmed.single().brightness)
        assertEquals("Desk", dimmed.single().name)

        val off = states.apply(JSONObject("""{"c":{"light.desk":{"+":{"s":"off"},"-":{"a":["brightness"]}}}}"""))
        assertFalse(off.single().on)
        assertEquals(0, off.single().brightness)
        assertTrue(off.single().dimmable)

        // A change for a device never added is skipped.
        assertTrue(states.apply(JSONObject("""{"c":{"light.other":{"+":{"s":"on"}}}}""")).isEmpty())
    }
}
