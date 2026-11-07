package cloud.samlo.couch.hass

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * Home Assistant's WebSocket API, subscribed to the row's devices: every change arrives as it
 * happens, whoever made it (Couch, a phone, a wall switch). A small RFC 6455 client of its own,
 * since Couch takes no libraries: text frames, pings, close. Runs on its own thread; [onStates]
 * and [onDown] are called there.
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
    @Volatile private var socket: Socket? = null
    @Volatile private var closed = false
    private val out = Any()
    private val states = EntityStates()

    fun start() {
        thread(name = "couch-hass-socket", isDaemon = true) {
            var rejected = false
            try {
                rejected = run()
            } catch (e: Exception) {
                // Dropped, refused or unreachable: the owner decides when to try again.
            } finally {
                runCatching { socket?.close() }
                if (!closed) onDown(rejected)
            }
        }
    }

    fun close() {
        closed = true
        runCatching { socket?.close() }
    }

    /** True when Home Assistant refused the token. */
    private fun run(): Boolean {
        val uri = URI(url)
        val secure = uri.scheme == "https"
        val port = if (uri.port > 0) uri.port else if (secure) 443 else 80
        val s = if (secure) SSLSocketFactory.getDefault().createSocket() else Socket()
        socket = s
        s.connect(InetSocketAddress(uri.host, port), CONNECT_MS)
        s.soTimeout = READ_MS
        if (closed) return false
        if (s is SSLSocket) {
            // A bare SSL socket checks the certificate chain but not that it names this host;
            // without this, any valid certificate could collect the token.
            s.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(uri.host, s.session)) {
                throw javax.net.ssl.SSLPeerUnverifiedException("Certificate doesn't match ${uri.host}")
            }
        }
        val input = BufferedInputStream(s.getInputStream())
        val output = s.getOutputStream()
        handshake(uri, port, input, output)
        val keepalive = thread(name = "couch-hass-ping", isDaemon = true) {
            try {
                var n = PING_ID
                while (!closed && !s.isClosed) {
                    Thread.sleep(PING_MS)
                    send(output, JSONObject().put("id", n++).put("type", "ping").toString())
                }
            } catch (e: Exception) {
                // The read loop notices the dead socket.
            }
        }
        try {
            while (!closed) {
                val text = readMessage(input, output) ?: return false
                val message = JSONObject(text)
                when (message.optString("type")) {
                    "auth_required" -> send(output, JSONObject().put("type", "auth").put("access_token", token).toString())
                    "auth_invalid" -> return true
                    "auth_ok" -> send(
                        output,
                        JSONObject().put("id", SUBSCRIBE_ID).put("type", "subscribe_entities").put("entity_ids", JSONArray(ids)).toString(),
                    )
                    "event" -> message.optJSONObject("event")?.let { states.apply(it) }?.takeIf { it.isNotEmpty() }?.let(onStates)
                }
            }
            return false
        } finally {
            keepalive.interrupt()
        }
    }

    private fun handshake(uri: URI, port: Int, input: InputStream, output: OutputStream) {
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
        val host = if (port == 80 || port == 443) uri.host else "${uri.host}:$port"
        val request = "GET /api/websocket HTTP/1.1\r\nHost: $host\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
        output.write(request.toByteArray())
        output.flush()
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0 || head.size() > MAX_HEAD) throw java.io.IOException("No handshake")
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
        }
        val lines = head.toString("UTF-8").split("\r\n")
        if (lines.firstOrNull()?.split(' ')?.getOrNull(1) != "101") throw java.io.IOException("Not upgraded: ${lines.firstOrNull()}")
        val accept = lines.firstOrNull { it.startsWith("sec-websocket-accept:", ignoreCase = true) }?.substringAfter(':')?.trim()
        if (accept != acceptFor(key)) throw java.io.IOException("Bad accept")
    }

    /** The next whole text message, answering pings on the way; null when the server closes. */
    private fun readMessage(input: InputStream, output: OutputStream): String? {
        val message = ByteArrayOutputStream()
        while (true) {
            val b0 = input.read()
            val b1 = input.read()
            if (b0 < 0 || b1 < 0) return null
            val opcode = b0 and 0x0F
            val fin = b0 and 0x80 != 0
            var length = (b1 and 0x7F).toLong()
            if (length == 126L) {
                length = ((readByte(input) shl 8) or readByte(input)).toLong()
            } else if (length == 127L) {
                length = (0 until 8).fold(0L) { acc, _ -> (acc shl 8) or readByte(input).toLong() }
            }
            if (length > MAX_MESSAGE) throw java.io.IOException("Message too large")
            val mask = if (b1 and 0x80 != 0) ByteArray(4) { readByte(input).toByte() } else null
            val payload = ByteArray(length.toInt())
            var read = 0
            while (read < payload.size) {
                val n = input.read(payload, read, payload.size - read)
                if (n < 0) return null
                read += n
            }
            mask?.let { m -> for (i in payload.indices) payload[i] = (payload[i].toInt() xor m[i % 4].toInt()).toByte() }
            when (opcode) {
                OP_CLOSE -> return null
                OP_PING -> frame(output, OP_PONG, payload)
                OP_PONG -> Unit
                OP_TEXT, OP_CONTINUATION -> {
                    message.write(payload)
                    if (message.size() > MAX_MESSAGE) throw java.io.IOException("Message too large")
                    if (fin) return message.toString("UTF-8")
                }
            }
        }
    }

    private fun readByte(input: InputStream): Int {
        val b = input.read()
        if (b < 0) throw java.io.EOFException()
        return b
    }

    private fun send(output: OutputStream, text: String) = frame(output, OP_TEXT, text.toByteArray(Charsets.UTF_8))

    private fun frame(output: OutputStream, opcode: Int, payload: ByteArray) {
        synchronized(out) {
            output.write(encodeFrame(opcode, payload, ByteArray(4).also { SecureRandom().nextBytes(it) }))
            output.flush()
        }
    }

    companion object {
        private const val CONNECT_MS = 4000
        /** Longer than two pings: a silent socket this long is dead. */
        private const val READ_MS = 75_000
        private const val PING_MS = 30_000L
        private const val SUBSCRIBE_ID = 1
        private const val PING_ID = 2
        private const val MAX_HEAD = 8 * 1024
        private const val MAX_MESSAGE = 4 * 1024 * 1024
        private const val OP_CONTINUATION = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /** What the server must answer to [key] in `Sec-WebSocket-Accept`. */
        fun acceptFor(key: String): String =
            Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray()))

        /** A client frame: final, masked with [mask] as clients must. */
        fun encodeFrame(opcode: Int, payload: ByteArray, mask: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(0x80 or opcode)
            when {
                payload.size < 126 -> out.write(0x80 or payload.size)
                payload.size < 65536 -> {
                    out.write(0x80 or 126)
                    out.write(payload.size shr 8)
                    out.write(payload.size and 0xFF)
                }
                else -> {
                    out.write(0x80 or 127)
                    for (shift in 56 downTo 0 step 8) out.write(((payload.size.toLong() shr shift) and 0xFF).toInt())
                }
            }
            out.write(mask)
            for (i in payload.indices) out.write(payload[i].toInt() xor mask[i % 4].toInt())
            return out.toByteArray()
        }
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
