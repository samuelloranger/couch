package cloud.samlo.couch.sync

import cloud.samlo.couch.phone.FrameCipher
import cloud.samlo.couch.phone.Frames
import cloud.samlo.couch.phone.Handshake
import cloud.samlo.couch.phone.PhoneHost
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

    /** Called when the other side ended a pairing the user was still being asked about: true if it said the codes differ, false if it went away. */
    @Volatile var onAborted: ((Boolean) -> Unit)? = null

    /** The kind of peer the page that opened pairing wants; any other kind that knocks is turned away. */
    @Volatile var pairKind: String = Peer.TV

    /** Called after a pairing this TV answered went through. */
    @Volatile var onPaired: ((Peer) -> Unit)? = null

    /** Where authenticated phone sessions go; null answers none. */
    @Volatile var phones: PhoneHost? = null

    /** Whether snapshots are shared right now: the server also runs for phones alone. */
    @Volatile var shares: () -> Boolean = { true }

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
                    if (bound.isClosed) return@thread
                    Thread.sleep(ACCEPT_RETRY_MS) // e.g. out of descriptors: try again rather than go deaf
                    continue
                }
                val from = client.inetAddress
                if (live.incrementAndGet() > MAX_CONNECTIONS || !admit(from)) {
                    live.decrementAndGet()
                    runCatching { client.close() }
                    continue
                }
                thread(name = "couch-sync-conn", isDaemon = true) {
                    var kept = false
                    try {
                        kept = handle(client)
                    } catch (e: Exception) {
                        // Anything a peer can break is that peer's problem: the connection just ends.
                    } finally {
                        // A phone session owns its socket now, and no longer counts against the short-connection budget.
                        if (!kept) runCatching { client.close() }
                        live.decrementAndGet()
                        release(from)
                    }
                }
            }
        }
        return bound.localPort
    }

    /** One host can't hold every slot with idle sockets. */
    private val perHost = HashMap<java.net.InetAddress, Int>()

    private fun admit(from: java.net.InetAddress): Boolean = synchronized(perHost) {
        val n = perHost[from] ?: 0
        if (n >= MAX_PER_HOST) false else { perHost[from] = n + 1; true }
    }

    private fun release(from: java.net.InetAddress) = synchronized(perHost) {
        val n = (perHost[from] ?: 1) - 1
        if (n <= 0) perHost.remove(from) else perHost[from] = n
    }

    @Synchronized fun stop() {
        runCatching { socket?.close() }
        socket = null
    }

    /** True when the connection was handed to a phone session, which closes it itself. */
    private fun handle(client: Socket): Boolean {
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
                "phone" -> return phone(client, request, input, output)
            }
            return false
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
        // Phones are never asked for snapshots, and nobody is while sharing is off.
        val peer = peers.get(id)?.takeIf { !it.isPhone && shares() } ?: return
        val nonce = Wire.unb64(request.getString("nonce"))
        if (nonce.size != NONCE_BYTES) return
        val proof = Wire.unb64(request.getString("proof"))
        if (!MessageDigest.isEqual(proof, Crypto.requestProof(peer.key, nonce, id))) return
        val sealed = Crypto.seal(peer.key, current().toByteArray(), nonce)
        Wire.write(output, JSONObject().put("data", Wire.b64(sealed)))
    }

    /**
     * A phone opening its long-lived session: it proves it holds the link key, the TV proves the
     * same back, and the encrypted session starts. Anything wrong closes without a word.
     */
    private fun phone(client: Socket, request: JSONObject, input: DataInputStream, output: DataOutputStream): Boolean {
        val host = phones ?: return false
        val id = request.getString("id")
        val peer = peers.get(id)?.takeIf { it.isPhone } ?: return false
        val cn = Wire.unb64(request.getString("nonce"))
        if (cn.size != Handshake.NONCE_BYTES) return false
        val proof = Wire.unb64(request.getString("proof"))
        if (!MessageDigest.isEqual(proof, Handshake.requestProof(peer.key, cn, id))) return false
        val sn = Crypto.random(Handshake.NONCE_BYTES)
        Wire.write(output, JSONObject().put("nonce", Wire.b64(sn)).put("proof", Wire.b64(Handshake.replyProof(peer.key, cn, sn))))
        // The proof above doesn't cover sn, so a replayed request would get this far: only a phone
        // that holds the key can send a first frame that opens, and only then does it take a slot.
        val keys = Handshake.keys(peer.key, cn, sn)
        val opener = FrameCipher(keys.clientToServer)
        client.soTimeout = FIRST_FRAME_MS
        val first = try {
            Frames.read(input, opener)
        } catch (e: IOException) {
            return false
        }
        return host.adopt(peer, client, input, opener, FrameCipher(keys.serverToClient), first)
    }

    /** Building the snapshot reads the usage log, so paired TVs asking in a burst share one. */
    @Synchronized private fun current(): String {
        val now = System.currentTimeMillis()
        cached?.let { (at, json) -> if (now - at < CACHE_MS) return json }
        return snapshot().toJson().also { cached = now to it }
    }

    /**
     * Pairing. The one pairing slot is taken before this TV answers with its key, so a second
     * attempt is turned away at once instead of being shown a code the TV will never confirm. The
     * other side's answer is read while the user is still asked, so a phone that says the codes
     * differ, or goes away, closes the question here too ([onAborted]).
     */
    private fun pair(client: Socket, limit: Limit, first: JSONObject, input: DataInputStream, output: DataOutputStream) {
        val ask = onPair
        val kind = Peer.kindOf(first.optString("kind"))
        if (ask == null || kind != pairKind || !pairing.compareAndSet(false, true)) {
            Wire.write(output, JSONObject().put("error", "not-pairing"))
            return
        }
        try {
            val commit = Wire.unb64(first.getString("commit"))
            val theirId = first.getString("id").take(Snapshot.MAX_NAME)
            val theirName = first.getString("name").take(Snapshot.MAX_NAME)
            val mine = Crypto.keyPair()
            // The slot is held from here, so a peer that stalls mid-exchange gives it back quickly.
            client.soTimeout = EXCHANGE_MS
            Wire.write(output, JSONObject().put("pub", Wire.b64(mine.publicKey)).put("id", peers.id).put("name", peers.name))
            val theirs = Wire.unb64(Wire.read(input, Wire.MAX_REQUEST).getString("pub"))
            if (!Crypto.commit(theirs).contentEquals(commit)) return
            val secret = Crypto.agree(mine, theirs)
            val code = Crypto.sas(secret, theirs, mine.publicKey)
            limit.set(ANSWER_MS.toLong())
            client.soTimeout = ANSWER_MS
            val local = java.util.concurrent.ArrayBlockingQueue<Boolean>(1)
            thread(name = "couch-sync-ask", isDaemon = true) {
                val matched = runCatching { ask(theirName, code) }.getOrDefault(false)
                local.offer(matched)
                // Said no here: stop waiting for the other side.
                if (!matched) runCatching { client.shutdownInput() }
            }
            val theirAnswer = try {
                Wire.read(input, Wire.MAX_REQUEST).getBoolean("ok")
            } catch (e: IOException) {
                null
            } catch (e: org.json.JSONException) {
                null
            }
            if (theirAnswer != true) {
                // The other side ended it first: close the question still open on this screen.
                if (local.peek() == null) onAborted?.invoke(theirAnswer == false)
                runCatching { Wire.write(output, JSONObject().put("done", false)) }
                return
            }
            val matched = local.poll(ANSWER_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS) ?: false
            if (matched && theirId != peers.id) {
                val peer = Peer(theirId, theirName, Crypto.linkKey(secret, theirs, mine.publicKey), kind = kind)
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
        private const val MAX_PER_HOST = 2
        private const val ACCEPT_RETRY_MS = 500L
        private const val READ_MS = 10_000
        private const val HANDSHAKE_MS = 15_000L
        private const val EXCHANGE_MS = 3_000
        /** A phone sends `hello` straight after the handshake. */
        private const val FIRST_FRAME_MS = 5_000
        private const val CACHE_MS = 30_000L
        /** Long enough for two people to look at two screens. */
        const val ANSWER_MS = 130_000
    }
}
