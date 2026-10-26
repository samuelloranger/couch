package cloud.samlo.couch.sync

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** The asking side of [Server]: fetch a paired TV's snapshot, or pair with a TV that has pairing open. */
object Client {
    private const val CONNECT_MS = 4_000
    private const val READ_MS = 10_000

    sealed class Pairing {
        class Paired(val peer: Peer) : Pairing()
        /** One of the two people said the codes differ, or the other TV went away before they agreed. */
        object Declined : Pairing()
        /** The other TV isn't taking pairings, or couldn't be reached. */
        object Unavailable : Pairing()
    }

    /** The peer's snapshot, or null when it can't be reached, doesn't answer as that peer, or sends junk. */
    fun pull(host: String, port: Int, me: Peers, peer: Peer): Snapshot? = try {
        connect(host, port) { _, input, output ->
            val nonce = Crypto.random(Server.NONCE_BYTES)
            Wire.write(
                output,
                JSONObject().put("op", "snapshot").put("id", me.id).put("nonce", Wire.b64(nonce))
                    .put("proof", Wire.b64(Crypto.requestProof(peer.key, nonce, me.id))),
            )
            val sealed = Wire.unb64(Wire.read(input).getString("data"))
            Crypto.open(peer.key, sealed, nonce)?.let { Snapshot.fromJson(String(it)) }?.takeIf { it.deviceId == peer.id }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Pairs with the TV at [host]:[port]. [ask] gets that TV's name and the six digits both screens
     * show, blocks while the user compares them, and returns whether they matched. On success the
     * new peer is already saved in [me].
     */
    fun pair(host: String, port: Int, me: Peers, ask: (String, String) -> Boolean): Pairing = try {
        connect(host, port) { socket, input, output ->
            val mine = Crypto.keyPair()
            Wire.write(
                output,
                JSONObject().put("op", "pair1").put("id", me.id).put("name", me.name).put("commit", Wire.b64(Crypto.commit(mine.publicKey))),
            )
            val reply = Wire.read(input)
            if (reply.has("error")) return@connect Pairing.Unavailable
            val theirs = Wire.unb64(reply.getString("pub"))
            val theirId = reply.getString("id").take(Snapshot.MAX_NAME)
            val theirName = reply.getString("name").take(Snapshot.MAX_NAME)
            Wire.write(output, JSONObject().put("pub", Wire.b64(mine.publicKey)))
            val secret = Crypto.agree(mine, theirs)
            val code = Crypto.sas(secret, mine.publicKey, theirs)
            // Two people comparing two screens take longer than a read normally may.
            socket.soTimeout = Server.ANSWER_MS
            val matched = ask(theirName, code)
            Wire.write(output, JSONObject().put("ok", matched))
            if (!matched || theirId == me.id) return@connect Pairing.Declined
            if (!Wire.read(input).getBoolean("done")) return@connect Pairing.Declined
            Peer(theirId, theirName, Crypto.linkKey(secret, mine.publicKey, theirs)).also(me::add).let { Pairing.Paired(it) }
        }
    } catch (e: IOException) {
        Pairing.Unavailable
    } catch (e: java.security.GeneralSecurityException) {
        Pairing.Unavailable
    } catch (e: org.json.JSONException) {
        Pairing.Unavailable
    }

    private fun <T> connect(host: String, port: Int, talk: (Socket, DataInputStream, DataOutputStream) -> T): T =
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_MS)
            socket.soTimeout = READ_MS
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            talk(socket, input, output)
        }
}
