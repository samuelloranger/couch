package cloud.samlo.couch.sync

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * What this TV answers to its paired TVs: its own snapshot, encrypted for the one asking, and the
 * pairing handshake while the user has the Add a TV screen open. Every connection is short, bounded
 * and handled alone, so a stranger on the network can cost a few sockets and nothing else.
 */
class Server(
    private val peers: Peers,
    /** This TV's snapshot as it is right now. */
    private val snapshot: () -> Snapshot,
) {
    /**
     * Non-null while pairing is open. Called with the other TV's name and the code to compare;
     * blocks until the user answers, and returns whether the codes matched.
     */
    @Volatile var onPair: ((String, String) -> Boolean)? = null

    private var socket: ServerSocket? = null
    private val live = AtomicInteger()
    private val pairing = java.util.concurrent.atomic.AtomicBoolean()

    /** The port in use: [PORT], or any free one when that is taken. Idempotent. */
    @Synchronized fun start(): Int {
        socket?.let { return it.localPort }
        val bound = try {
            ServerSocket(PORT)
        } catch (e: IOException) {
            ServerSocket(0)
        }
        socket = bound
        thread(name = "couch-sync-server", isDaemon = true) {
            while (!bound.isClosed) {
                val client = try {
                    bound.accept()
                } catch (e: IOException) {
                    return@thread
                }
                if (live.incrementAndGet() > MAX_CONNECTIONS) {
                    live.decrementAndGet()
                    runCatching { client.close() }
                    continue
                }
                thread(name = "couch-sync-conn", isDaemon = true) {
                    try {
                        handle(client)
                    } catch (e: Exception) {
                        // Anything a peer can break is that peer's problem: the connection just ends.
                    } finally {
                        runCatching { client.close() }
                        live.decrementAndGet()
                    }
                }
            }
        }
        return bound.localPort
    }

    @Synchronized fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun handle(client: Socket) {
        client.soTimeout = READ_MS
        val input = DataInputStream(BufferedInputStream(client.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(client.getOutputStream()))
        val request = Wire.read(input)
        when (request.getString("op")) {
            "snapshot" -> snapshot(request, output)
            "pair1" -> pair(client, request, input, output)
        }
    }

    /** An unknown asker gets silence; a known one gets the snapshot only it can read. */
    private fun snapshot(request: JSONObject, output: DataOutputStream) {
        val peer = peers.get(request.getString("id")) ?: return
        val nonce = Wire.unb64(request.getString("nonce"))
        if (nonce.size != NONCE_BYTES) return
        val sealed = Crypto.seal(peer.key, snapshot().toJson().toByteArray(), nonce)
        Wire.write(output, JSONObject().put("data", Wire.b64(sealed)))
    }

    private fun pair(client: Socket, first: JSONObject, input: DataInputStream, output: DataOutputStream) {
        val ask = onPair
        if (ask == null || !pairing.compareAndSet(false, true)) {
            Wire.write(output, JSONObject().put("error", "not-pairing"))
            return
        }
        try {
            val commit = Wire.unb64(first.getString("commit"))
            val theirId = first.getString("id").take(Snapshot.MAX_NAME)
            val theirName = first.getString("name").take(Snapshot.MAX_NAME)
            val mine = Crypto.keyPair()
            Wire.write(output, JSONObject().put("pub", Wire.b64(mine.publicKey)).put("id", peers.id).put("name", peers.name))
            val theirs = Wire.unb64(Wire.read(input).getString("pub"))
            if (!Crypto.commit(theirs).contentEquals(commit)) return
            val secret = Crypto.agree(mine, theirs)
            val code = Crypto.sas(secret, theirs, mine.publicKey)
            client.soTimeout = ANSWER_MS
            val matched = ask(theirName, code)
            val theirAnswer = Wire.read(input).getBoolean("ok")
            if (matched && theirAnswer && theirId != peers.id) {
                peers.add(Peer(theirId, theirName, Crypto.linkKey(secret, theirs, mine.publicKey)))
                Wire.write(output, JSONObject().put("done", true))
            } else {
                Wire.write(output, JSONObject().put("done", false))
            }
        } finally {
            pairing.set(false)
        }
    }

    companion object {
        /** Fixed, so a TV that mDNS can't find can still be reached by its address. */
        const val PORT = 47820
        const val NONCE_BYTES = 16
        private const val MAX_CONNECTIONS = 6
        private const val READ_MS = 10_000
        /** Long enough for two people to look at two screens. */
        const val ANSWER_MS = 130_000
    }
}
