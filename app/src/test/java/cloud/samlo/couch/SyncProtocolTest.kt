package cloud.samlo.couch

import cloud.samlo.couch.data.AppUsage
import cloud.samlo.couch.data.Usage
import cloud.samlo.couch.sync.Client
import cloud.samlo.couch.sync.Crypto
import cloud.samlo.couch.sync.Peer
import cloud.samlo.couch.sync.Peers
import cloud.samlo.couch.sync.Server
import cloud.samlo.couch.sync.Snapshot
import cloud.samlo.couch.sync.Wire
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.Socket
import java.time.LocalDate

class SyncProtocolTest {
    private val servers = ArrayList<Server>()

    @After
    fun stop() = servers.forEach(Server::stop)

    private fun tv(name: String): Pair<Peers, Server> {
        val peers = Peers.load(null, name)
        val server = Server(peers) {
            Snapshot(peers.id, peers.name, 1L, LocalDate.now(), mapOf("tv.app" to AppUsage(LongArray(Usage.DAYS) { 1L }, 2, 3L)))
        }
        servers += server
        return peers to server
    }

    @Test
    fun bothSidesComputeTheSameCodeAndKey() {
        val a = Crypto.keyPair()
        val b = Crypto.keyPair()
        val sa = Crypto.agree(a, b.publicKey)
        val sb = Crypto.agree(b, a.publicKey)
        assertArrayEquals(sa, sb)
        assertEquals(Crypto.sas(sa, a.publicKey, b.publicKey), Crypto.sas(sb, a.publicKey, b.publicKey))
        assertEquals(6, Crypto.sas(sa, a.publicKey, b.publicKey).length)
        assertArrayEquals(Crypto.linkKey(sa, a.publicKey, b.publicKey), Crypto.linkKey(sb, a.publicKey, b.publicKey))
    }

    @Test
    fun aMiddleManEndsWithADifferentCodeOnEachSide() {
        val a = Crypto.keyPair()
        val b = Crypto.keyPair()
        val m1 = Crypto.keyPair()
        val m2 = Crypto.keyPair()
        // a talks to the middle man, who talks to b.
        val seenByA = Crypto.sas(Crypto.agree(a, m1.publicKey), a.publicKey, m1.publicKey)
        val seenByB = Crypto.sas(Crypto.agree(b, m2.publicKey), m2.publicKey, b.publicKey)
        assertNotEquals(seenByA, seenByB)
    }

    @Test
    fun sealedBytesOpenOnlyWithTheKeyAndTheRequest() {
        val key = Crypto.random(32)
        val nonce = Crypto.random(16)
        val sealed = Crypto.seal(key, "hello".toByteArray(), nonce)
        assertEquals("hello", String(Crypto.open(key, sealed, nonce)!!))
        assertNull(Crypto.open(Crypto.random(32), sealed, nonce))
        assertNull(Crypto.open(key, sealed, Crypto.random(16)))
        sealed[sealed.size - 1] = (sealed.last() + 1).toByte()
        assertNull(Crypto.open(key, sealed, nonce))
    }

    @Test
    fun twoTvsPairWhenBothSayTheCodesMatch() {
        val (aPeers, _) = tv("Living room")
        val (bPeers, bServer) = tv("Bedroom")
        val port = bServer.start()
        var seenByB: String? = null
        bServer.onPair = { _, code -> seenByB = code; true }
        var seenByA: String? = null
        val result = Client.pair("127.0.0.1", port, aPeers) { _, code -> seenByA = code; true }
        assertTrue(result is Client.Pairing.Paired)
        assertEquals(seenByA, seenByB)
        assertArrayEquals(aPeers.get(bPeers.id)!!.key, bPeers.get(aPeers.id)!!.key)
        assertEquals("Bedroom", aPeers.get(bPeers.id)!!.name)
        assertEquals("Living room", bPeers.get(aPeers.id)!!.name)
    }

    @Test
    fun nothingIsSavedWhenEitherSideDeclines() {
        val (aPeers, _) = tv("A")
        val (bPeers, bServer) = tv("B")
        val port = bServer.start()
        bServer.onPair = { _, _ -> false }
        assertTrue(Client.pair("127.0.0.1", port, aPeers) { _, _ -> true } is Client.Pairing.Declined)
        assertTrue(aPeers.all().isEmpty() && bPeers.all().isEmpty())
        bServer.onPair = { _, _ -> true }
        assertTrue(Client.pair("127.0.0.1", port, aPeers) { _, _ -> false } is Client.Pairing.Declined)
        assertTrue(aPeers.all().isEmpty() && bPeers.all().isEmpty())
    }

    @Test
    fun aTvThatIsNotPairingRefuses() {
        val (aPeers, _) = tv("A")
        val (_, bServer) = tv("B")
        val port = bServer.start()
        assertTrue(Client.pair("127.0.0.1", port, aPeers) { _, _ -> true } is Client.Pairing.Unavailable)
    }

    private fun paired(): Triple<Peers, Peers, Int> {
        val (aPeers, _) = tv("A")
        val (bPeers, bServer) = tv("B")
        val port = bServer.start()
        bServer.onPair = { _, _ -> true }
        assertTrue(Client.pair("127.0.0.1", port, aPeers) { _, _ -> true } is Client.Pairing.Paired)
        bServer.onPair = null
        return Triple(aPeers, bPeers, port)
    }

    @Test
    fun aPairedTvPullsTheOthersSnapshot() {
        val (a, b, port) = paired()
        val snapshot = Client.pull("127.0.0.1", port, a, a.get(b.id)!!)
        assertNotNull(snapshot)
        assertEquals(b.id, snapshot!!.deviceId)
        assertEquals(2, snapshot.apps.getValue("tv.app").opens)
    }

    @Test
    fun aStrangerGetsNothing() {
        val (_, b, port) = paired()
        val (stranger, _) = tv("Stranger")
        val fake = Peer(b.id, "B", Crypto.random(32))
        assertNull(Client.pull("127.0.0.1", port, stranger, fake))
    }

    @Test
    fun aWrongKeyCannotReadTheAnswer() {
        val (a, b, port) = paired()
        val wrong = Peer(b.id, "B", Crypto.random(32))
        assertNull(Client.pull("127.0.0.1", port, a, wrong))
    }

    @Test
    fun anOversizedOrBrokenFrameJustEndsTheConnection() {
        val (_, bServer) = tv("B")
        val port = bServer.start()
        Socket("127.0.0.1", port).use { socket ->
            DataOutputStream(socket.getOutputStream()).apply { writeInt(Wire.MAX_FRAME + 1); flush() }
            assertEquals(-1, DataInputStream(socket.getInputStream()).read())
        }
        Socket("127.0.0.1", port).use { socket ->
            DataOutputStream(socket.getOutputStream()).apply { writeInt(5); write("nope!".toByteArray()); flush() }
            assertEquals(-1, DataInputStream(socket.getInputStream()).read())
        }
        // Still serving afterwards.
        bServer.onPair = { _, _ -> true }
        assertTrue(Client.pair("127.0.0.1", port, tv("A").first) { _, _ -> true } is Client.Pairing.Paired)
    }

    @Test
    fun peersSurviveARestartAndForgetting() {
        val file = File.createTempFile("sync", ".json").apply { deleteOnExit() }
        val first = Peers.load(file, "Den")
        first.add(Peer("x", "Kitchen", Crypto.random(32)))
        first.store("x", Snapshot("x", "Kitchen", 1L, LocalDate.now(), emptyMap()), 99L)
        first.name = "  Den TV  "
        val again = Peers.load(file, "Ignored")
        assertEquals(first.id, again.id)
        assertEquals("Den TV", again.name)
        assertEquals(99L, again.get("x")!!.lastSync)
        assertEquals(1, again.snapshots().size)
        again.forget("x")
        assertTrue(Peers.load(file, "Ignored").all().isEmpty())
        // A snapshot that arrives after the TV was forgotten is dropped.
        again.store("x", Snapshot("x", "Kitchen", 1L, LocalDate.now(), emptyMap()), 100L)
        assertFalse(again.all().any())
    }

    @Test
    fun aDamagedFileStartsAFreshIdentityAndIsKeptAside() {
        val file = File.createTempFile("sync", ".json").apply { writeText("{ nope"); deleteOnExit() }
        val aside = File(file.parentFile, file.name + ".bad").apply { deleteOnExit() }
        val peers = Peers.load(file, "Den")
        assertEquals("Den", peers.name)
        assertTrue(peers.id.isNotEmpty())
        assertEquals("{ nope", aside.readText())
    }

    @Test
    fun aRequestWithoutTheKeyGetsNoAnswer() {
        val (_, b, port) = paired()
        Socket("127.0.0.1", port).use { socket ->
            val out = DataOutputStream(socket.getOutputStream())
            Wire.write(
                out,
                org.json.JSONObject().put("op", "snapshot").put("id", b.all().first().id)
                    .put("nonce", Wire.b64(Crypto.random(16))).put("proof", Wire.b64(Crypto.random(32))),
            )
            assertEquals(-1, DataInputStream(socket.getInputStream()).read())
        }
    }

    @Test
    fun aStalledHandshakeDoesNotBlockPairing() {
        val (_, bServer) = tv("B")
        val port = bServer.start()
        bServer.onPair = { _, _ -> true }
        // Knocks and says nothing more; the one pairing slot is only taken once a handshake is real.
        val staller = Socket("127.0.0.1", port)
        try {
            val out = DataOutputStream(staller.getOutputStream())
            Wire.write(out, org.json.JSONObject().put("op", "pair1").put("id", "x").put("name", "x").put("commit", Wire.b64(Crypto.random(32))))
            assertTrue(Client.pair("127.0.0.1", port, tv("A").first) { _, _ -> true } is Client.Pairing.Paired)
        } finally {
            staller.close()
        }
    }

    @Test
    fun anOversizedFirstFrameIsRefusedBeforeItIsRead() {
        val (_, bServer) = tv("B")
        val port = bServer.start()
        Socket("127.0.0.1", port).use { socket ->
            DataOutputStream(socket.getOutputStream()).apply { writeInt(Wire.MAX_REQUEST + 1); flush() }
            assertEquals(-1, DataInputStream(socket.getInputStream()).read())
        }
    }
}
