package cloud.samlo.couch.sync

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
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

    /** Called after a pairing this TV answered went through. */
    @Volatile var onPaired: ((Peer) -> Unit)? = null

    private val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "couch-sync-watchdog").apply { isDaemon = true }
    }
    private var cached: Pair<Long, String>? = null
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
        val limit = Limit(client)
        try {
            limit.set(HANDSHAKE_MS)
            val input = DataInputStream(BufferedInputStream(client.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(client.getOutputStream()))
            val request = Wire.read(input, Wire.MAX_REQUEST)
            when (request.getString("op")) {
                "snapshot" -> snapshot(request, output)
                "pair1" -> pair(client, limit, request, input, output)
            }
        } finally {
            limit.cancel()
        }
    }

    /** Closes a connection that outstays its time, however slowly it dribbles bytes. */
    private inner class Limit(private val client: Socket) {
        private var task: java.util.concurrent.ScheduledFuture<*>? = null

        @Synchronized fun set(ms: Long) {
            task?.cancel(false)
            task = watchdog.schedule({ runCatching { client.close() } }, ms, java.util.concurrent.TimeUnit.MILLISECONDS)
        }

        @Synchronized fun cancel() {
            task?.cancel(false)
        }
    }

    /** An unknown or unproven asker gets silence; a proven one gets the snapshot only it can read. */
    private fun snapshot(request: JSONObject, output: DataOutputStream) {
        val id = request.getString("id")
        val peer = peers.get(id) ?: return
        val nonce = Wire.unb64(request.getString("nonce"))
        if (nonce.size != NONCE_BYTES) return
        val proof = Wire.unb64(request.getString("proof"))
        if (!MessageDigest.isEqual(proof, Crypto.requestProof(peer.key, nonce, id))) return
        val sealed = Crypto.seal(peer.key, current().toByteArray(), nonce)
        Wire.write(output, JSONObject().put("data", Wire.b64(sealed)))
    }

    /** Building the snapshot reads the usage log, so paired TVs asking in a burst share one. */
    @Synchronized private fun current(): String {
        val now = System.currentTimeMillis()
        cached?.let { (at, json) -> if (now - at < CACHE_MS) return json }
        return snapshot().toJson().also { cached = now to it }
    }

    private fun pair(client: Socket, limit: Limit, first: JSONObject, input: DataInputStream, output: DataOutputStream) {
        if (onPair == null) {
            Wire.write(output, JSONObject().put("error", "not-pairing"))
            return
        }
        val commit = Wire.unb64(first.getString("commit"))
        val theirId = first.getString("id").take(Snapshot.MAX_NAME)
        val theirName = first.getString("name").take(Snapshot.MAX_NAME)
        val mine = Crypto.keyPair()
        Wire.write(output, JSONObject().put("pub", Wire.b64(mine.publicKey)).put("id", peers.id).put("name", peers.name))
        val theirs = Wire.unb64(Wire.read(input, Wire.MAX_REQUEST).getString("pub"))
        if (!Crypto.commit(theirs).contentEquals(commit)) return
        val secret = Crypto.agree(mine, theirs)
        val code = Crypto.sas(secret, theirs, mine.publicKey)
        // Only a handshake that got this far can hold the one pairing slot, and only for as long as people need.
        val ask = onPair
        if (ask == null || !pairing.compareAndSet(false, true)) {
            Wire.write(output, JSONObject().put("error", "not-pairing"))
            return
        }
        try {
            limit.set(ANSWER_MS.toLong())
            client.soTimeout = ANSWER_MS
            val matched = ask(theirName, code)
            val theirAnswer = Wire.read(input, Wire.MAX_REQUEST).getBoolean("ok")
            if (matched && theirAnswer && theirId != peers.id) {
                val peer = Peer(theirId, theirName, Crypto.linkKey(secret, theirs, mine.publicKey))
                peers.add(peer)
                Wire.write(output, JSONObject().put("done", true))
                onPaired?.invoke(peer)
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
        private const val HANDSHAKE_MS = 15_000L
        private const val CACHE_MS = 30_000L
        /** Long enough for two people to look at two screens. */
        const val ANSWER_MS = 130_000
    }
}
