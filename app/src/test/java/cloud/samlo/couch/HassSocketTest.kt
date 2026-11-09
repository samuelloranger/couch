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
    private val socket = HassSocket("http://x", "t", emptyList(), {}, {})

    /** A server frame: unmasked, [first] byte carrying FIN and opcode. */
    private fun server(first: Int, payload: String) = byteArrayOf(first.toByte(), payload.length.toByte()) + payload.toByteArray()

    /** Hands out one byte per read, as a slow network would. */
    private fun trickle(bytes: ByteArray) = object : java.io.InputStream() {
        var at = 0
        override fun read() = if (at < bytes.size) bytes[at++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val c = read()
            if (c < 0) return -1
            b[off] = c.toByte()
            return 1
        }
    }

    @Test
    fun fragmentsJoinAndAPingBetweenThemIsAnswered() {
        val wire = server(0x01, "{\"a\":") + server(0x89, "hi") + server(0x80, "1}")
        val sent = java.io.ByteArrayOutputStream()
        assertEquals("{\"a\":1}", socket.readMessage(trickle(wire), sent))
        // The pong echoes the ping's payload, masked: 2 header bytes, 4 mask bytes, 2 payload bytes.
        val pong = sent.toByteArray()
        assertEquals(8, pong.size)
        assertEquals(0x8A, pong[0].toInt() and 0xFF)
        assertEquals(0x80 or 2, pong[1].toInt() and 0xFF)
    }

    @Test
    fun closeEndsTheStream() {
        assertEquals(null, socket.readMessage(java.io.ByteArrayInputStream(server(0x88, "")), java.io.ByteArrayOutputStream()))
    }

    @Test
    fun oversizedOrNegativeLengthsAreRejected() {
        val huge = byteArrayOf(0x81.toByte(), 127) + ByteArray(8).also { it[4] = 0x7F } // ~2 GB
        val negative = byteArrayOf(0x81.toByte(), 127) + ByteArray(8).also { it[0] = 0x80.toByte() }
        for (wire in listOf(huge, negative)) {
            try {
                socket.readMessage(java.io.ByteArrayInputStream(wire), java.io.ByteArrayOutputStream())
                org.junit.Assert.fail("accepted")
            } catch (e: java.io.IOException) {
                // Rejected before any payload was allocated.
            }
        }
    }

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
