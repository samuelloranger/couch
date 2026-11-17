package cloud.samlo.couch.hass

import cloud.samlo.couch.data.Http
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Home Assistant's WebSocket API, subscribed to the row's devices: every change arrives as it
 * happens, whoever made it (Couch, a phone, a wall switch). [onStates] and [onDown] are called on
 * OkHttp's threads.
 */
class HassSocket(
    private val url: String,
    private val token: String,
    private val ids: List<String>,
    /** Devices whose state arrived or changed. */
    private val onStates: (List<HassDevice>) -> Unit,
    /** The connection ended; [rejected] when Home Assistant refused the token, so retrying is pointless. */
    private val onDown: (rejected: Boolean) -> Unit,
) {
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closed = false
    private val states = EntityStates()

    fun start() {
        // LocalNetwork resolves the host, so not on the caller's (main) thread.
        thread(name = "couch-hass-socket", isDaemon = true) {
            if (!LocalNetwork.allowed(url)) return@thread down(false)
            val wsUrl = url.replaceFirst(Regex("^http"), "ws") + "/api/websocket"
            socket = client.newWebSocket(Request.Builder().url(wsUrl).build(), Listener())
            if (closed) socket?.cancel()
        }
    }

    fun close() {
        closed = true
        socket?.cancel()
    }

    @Volatile private var reported = false

    /** Reports the end once, and not after [close]: the owner already let go. */
    private fun down(rejected: Boolean) {
        if (reported) return
        reported = true
        if (!closed) onDown(rejected)
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (message.optString("type")) {
                "auth_required" -> webSocket.send(JSONObject().put("type", "auth").put("access_token", token).toString())
                "auth_invalid" -> {
                    down(true)
                    webSocket.cancel()
                }
                "auth_ok" -> webSocket.send(
                    JSONObject().put("id", SUBSCRIBE_ID).put("type", "subscribe_entities").put("entity_ids", JSONArray(ids)).toString(),
                )
                "event" -> message.optJSONObject("event")?.let { states.apply(it) }?.takeIf { it.isNotEmpty() }?.let(onStates)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
            down(false)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = down(false)

        // Dropped, refused, unreachable or a missed pong: the owner decides when to try again.
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = down(false)
    }

    private companion object {
        const val SUBSCRIBE_ID = 1
        val client = Http.client.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            // Home Assistant is quiet while nothing changes; the pings find a dead socket instead.
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }
}

/** Each device's state and attributes as Home Assistant last sent them, which changes merge into. */
class EntityStates {
    private val states = HashMap<String, JSONObject>()

    /**
     * One `subscribe_entities` event, in Home Assistant's compressed form: `a` adds whole states
     * (`s` the state, `a` its attributes), `c` changes them (`+` merged in, `-` attributes removed).
     */
    fun apply(event: JSONObject): List<HassDevice> {
        val touched = LinkedHashSet<String>()
        event.optJSONObject("a")?.let { added ->
            added.keys().forEach { id ->
                val s = added.getJSONObject(id)
                states[id] = JSONObject().put("entity_id", id).put("state", s.optString("s")).put("attributes", s.optJSONObject("a") ?: JSONObject())
                touched += id
            }
        }
        event.optJSONObject("c")?.let { changes ->
            changes.keys().forEach { id ->
                val current = states[id] ?: return@forEach
                val attributes = current.getJSONObject("attributes")
                val change = changes.getJSONObject(id)
                change.optJSONObject("+")?.let { plus ->
                    if (plus.has("s")) current.put("state", plus.optString("s"))
                    plus.optJSONObject("a")?.let { a -> a.keys().forEach { attributes.put(it, a.get(it)) } }
                }
                change.optJSONObject("-")?.optJSONArray("a")?.let { gone -> (0 until gone.length()).forEach { attributes.remove(gone.getString(it)) } }
                touched += id
            }
        }
        return touched.mapNotNull { states[it]?.let(HassJson::state) }
    }
}
