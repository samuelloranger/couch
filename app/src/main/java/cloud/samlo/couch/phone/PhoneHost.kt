package cloud.samlo.couch.phone

import cloud.samlo.couch.sync.Peer
import java.io.DataInputStream
import java.net.Socket

/** What the sync server hands an authenticated phone connection to. */
interface PhoneHost {
    /**
     * Takes over [client] for [peer], whose handshake succeeded and whose first encrypted frame
     * ([first], plaintext, opened by [opener]) arrived; [input] is the stream they were read from.
     * False when there is no room, and the server closes the socket.
     */
    fun adopt(peer: Peer, client: Socket, input: DataInputStream, opener: FrameCipher, sealer: FrameCipher, first: ByteArray): Boolean
}
