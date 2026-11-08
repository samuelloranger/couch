package cloud.samlo.couch

import cloud.samlo.couch.phone.FrameCipher
import cloud.samlo.couch.phone.Frames
import cloud.samlo.couch.phone.Handshake
import cloud.samlo.couch.phone.PhoneHost
import cloud.samlo.couch.phone.Session
import cloud.samlo.couch.phone.SessionKeys
import cloud.samlo.couch.sync.Crypto
import cloud.samlo.couch.sync.Peer
import cloud.samlo.couch.sync.Peers
import cloud.samlo.couch.sync.Server
import cloud.samlo.couch.sync.Snapshot
import cloud.samlo.couch.sync.Wire
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/** The phone handshake and an encrypted session over loopback, against the real [Server] and [Session]. */
class PhoneSessionTest {
    private val servers = ArrayList<Server>()
    private val sockets = ArrayList<Socket>()
    private val adopted = CopyOnWriteArrayList<Session>()

    /** Echoes every message back with `t` = "echo". */
    private val host = object : PhoneHost, Session.Listener {
        override fun adopt(peer: Peer, client: Socket, input: DataInputStream, keys: SessionKeys): Boolean {
            val session = Session(peer, client, input, keys, this)
            adopted.add(session)
            session.start()
            return true
        }

        override fun onMessage(session: Session, message: JSONObject) {
            session.send(JSONObject().put("t", "echo").put("n", message.optInt("n")))
        }

        override fun onClosed(session: Session) = Unit
    }

    @After
    fun stop() {
        servers.forEach(Server::stop)
        sockets.forEach { runCatching { it.close() } }
        adopted.forEach(Session::close)
    }

    private fun tvWithPhone(kind: String = Peer.PHONE): Triple<Server, Peers, ByteArray> {
        val peers = Peers.load(null, "tv")
        val link = Crypto.random(32)
        peers.add(Peer("phone-1", "My phone", link, kind = kind))
        val server = Server(peers) { Snapshot(peers.id, peers.name, 1L, LocalDate.now(), emptyMap()) }
        server.phones = host
        servers += server
        return Triple(server, peers, link)
    }

    private class Line(val socket: Socket, val input: DataInputStream, val output: DataOutputStream)

    private fun connect(port: Int): Line {
        val socket = Socket("127.0.0.1", port).also { it.soTimeout = 5_000; sockets += it }
        return Line(socket, DataInputStream(BufferedInputStream(socket.getInputStream())), DataOutputStream(BufferedOutputStream(socket.getOutputStream())))
    }

    private fun open(line: Line, link: ByteArray, id: String = "phone-1", proof: ((ByteArray) -> ByteArray)? = null): Pair<FrameCipher, FrameCipher>? {
        val cn = Crypto.random(16)
        val p = proof?.invoke(cn) ?: Handshake.requestProof(link, cn, id)
        Wire.write(line.output, JSONObject().put("op", "phone").put("id", id).put("nonce", Wire.b64(cn)).put("proof", Wire.b64(p)))
        val reply = try {
            Wire.read(line.input)
        } catch (e: IOException) {
            return null
        }
        val sn = Wire.unb64(reply.getString("nonce"))
        assertTrue(Wire.unb64(reply.getString("proof")).contentEquals(Handshake.replyProof(link, cn, sn)))
        val keys = Handshake.keys(link, cn, sn)
        return FrameCipher(keys.clientToServer) to FrameCipher(keys.serverToClient)
    }

    private fun send(line: Line, cipher: FrameCipher, json: JSONObject) =
        Frames.write(line.output, cipher, byteArrayOf(1) + json.toString().toByteArray())

    private fun receive(line: Line, cipher: FrameCipher): JSONObject {
        val plain = Frames.read(line.input, cipher)
        assertEquals(1, plain[0].toInt())
        return JSONObject(String(plain, 1, plain.size - 1))
    }

    @Test
    fun aPairedPhoneOpensAnEncryptedSessionAndFramesFlowBothWays() {
        val (server, _, link) = tvWithPhone()
        val line = connect(server.start())
        val (toTv, fromTv) = open(line, link)!!
        for (n in 1..3) {
            send(line, toTv, JSONObject().put("t", "hello").put("n", n))
            val answer = receive(line, fromTv)
            assertEquals("echo", answer.getString("t"))
            assertEquals(n, answer.getInt("n"))
        }
        assertEquals(1, adopted.size)
    }

    @Test
    fun anUnknownIdABadProofOrATvPeerGetsSilence() {
        val (server, _, link) = tvWithPhone()
        assertEquals(null, open(connect(server.start()), link, id = "stranger"))
        assertEquals(null, open(connect(server.start()), link, proof = { ByteArray(32) }))
        assertEquals(null, open(connect(server.start()), ByteArray(32)))
        val (tvServer, _, tvLink) = tvWithPhone(kind = Peer.TV)
        assertEquals(null, open(connect(tvServer.start()), tvLink))
        assertTrue(adopted.isEmpty())
    }

    @Test
    fun aFrameThatDoesNotOpenEndsTheSession() {
        val (server, _, link) = tvWithPhone()
        val line = connect(server.start())
        val (toTv, _) = open(line, link)!!
        // Skip a counter: the TV can no longer open anything this phone sends.
        toTv.seal(byteArrayOf(1))
        send(line, toTv, JSONObject().put("t", "hello"))
        try {
            line.input.readInt()
            assertFalse("the TV kept the line open", true)
        } catch (e: IOException) {
            assertNotNull(e)
        }
    }

    @Test
    fun phonesAreNeverAnsweredASnapshotAndTvsOnlyWhileSharing() {
        val (server, peers, link) = tvWithPhone()
        val line = connect(server.start())
        val cn = Crypto.random(16)
        Wire.write(
            line.output,
            JSONObject().put("op", "snapshot").put("id", "phone-1").put("nonce", Wire.b64(cn)).put("proof", Wire.b64(Crypto.requestProof(link, cn, "phone-1"))),
        )
        try {
            Wire.read(line.input)
            assertFalse("a phone got a snapshot", true)
        } catch (e: IOException) {
            assertNotNull(e)
        }
        val tvLink = Crypto.random(32)
        peers.add(Peer("tv-2", "Other TV", tvLink))
        server.shares = { false }
        val off = connect(server.start())
        val n2 = Crypto.random(16)
        Wire.write(off.output, JSONObject().put("op", "snapshot").put("id", "tv-2").put("nonce", Wire.b64(n2)).put("proof", Wire.b64(Crypto.requestProof(tvLink, n2, "tv-2"))))
        try {
            Wire.read(off.input)
            assertFalse("a snapshot was shared while sharing is off", true)
        } catch (e: IOException) {
            assertNotNull(e)
        }
        server.shares = { true }
        val on = connect(server.start())
        Wire.write(on.output, JSONObject().put("op", "snapshot").put("id", "tv-2").put("nonce", Wire.b64(n2)).put("proof", Wire.b64(Crypto.requestProof(tvLink, n2, "tv-2"))))
        assertTrue(Wire.read(on.input).has("data"))
    }
}
