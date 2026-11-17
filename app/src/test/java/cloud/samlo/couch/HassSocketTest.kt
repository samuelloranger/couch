package cloud.samlo.couch

import cloud.samlo.couch.hass.EntityStates
import cloud.samlo.couch.hass.HassDevice
import cloud.samlo.couch.hass.HassSocket
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class HassSocketTest {
    /** Home Assistant's side: asks for auth, accepts only [goodToken], answers a subscribe with one light. */
    private fun homeAssistant(goodToken: String) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            webSocket.send("""{"type":"auth_required"}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val m = JSONObject(text)
            when (m.getString("type")) {
                "auth" -> webSocket.send(if (m.getString("access_token") == goodToken) """{"type":"auth_ok"}""" else """{"type":"auth_invalid"}""")
                "subscribe_entities" -> webSocket.send(
                    """{"id":${m.getInt("id")},"type":"event","event":{"a":{"light.desk":{"s":"on","a":{"friendly_name":"Desk"}}}}}""",
                )
            }
        }
    }

    /** Runs a socket against [server] and returns what it reported first: a device list or a rejection flag. */
    private fun firstReport(token: String): Any {
        MockWebServer().use { server ->
            server.enqueue(MockResponse.Builder().webSocketUpgrade(homeAssistant("good")).build())
            server.start()
            val reports = ArrayBlockingQueue<Any>(4)
            val socket = HassSocket(
                "http://127.0.0.1:${server.port}", token, listOf("light.desk"),
                onStates = { reports.add(it) },
                onDown = { rejected -> reports.add(rejected) },
            )
            socket.start()
            try {
                return reports.poll(5, TimeUnit.SECONDS) ?: error("no report")
            } finally {
                socket.close()
            }
        }
    }

    @Test
    fun authenticatesSubscribesAndStreamsStates() {
        @Suppress("UNCHECKED_CAST")
        val devices = firstReport("good") as List<HassDevice>
        assertEquals("light.desk", devices.single().id)
        assertTrue(devices.single().on)
    }

    @Test
    fun aRefusedTokenIsReportedAsRejected() {
        assertEquals(true, firstReport("bad"))
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
