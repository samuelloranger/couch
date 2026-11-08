package cloud.samlo.couch.phone

import cloud.samlo.couch.sync.Peer
import java.io.DataInputStream
import java.net.Socket

/** What the sync server hands an authenticated phone connection to. */
interface PhoneHost {
    /**
     * Takes over [client] for [peer], whose handshake already succeeded; [input] is the stream the
     * handshake was read from. False when there is no room, and the server closes the socket.
     */
    fun adopt(peer: Peer, client: Socket, input: DataInputStream, keys: SessionKeys): Boolean
}
