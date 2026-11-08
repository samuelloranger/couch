package cloud.samlo.couch.phone

import cloud.samlo.couch.sync.Crypto

/** The two keys of one phone session, one per direction so no key ever sees a counter twice. */
class SessionKeys(val clientToServer: ByteArray, val serverToClient: ByteArray)

/**
 * The proofs and keys of a phone session, all derived from the link key two paired devices keep.
 * The phone sends a nonce and proves it holds the key; the TV answers with its own nonce and a
 * proof, so each side knows the other is the paired one before anything private is said.
 */
object Handshake {
    const val NONCE_BYTES = 16

    /** What the phone sends with its nonce [cn]. */
    fun requestProof(link: ByteArray, cn: ByteArray, id: String): ByteArray =
        Crypto.mac(link, "couch-phone".toByteArray(), cn, id.toByteArray())

    /** What the TV answers with, once the phone's proof checked out. */
    fun replyProof(link: ByteArray, cn: ByteArray, sn: ByteArray): ByteArray =
        Crypto.mac(link, "couch-phone-ok".toByteArray(), cn, sn)

    fun keys(link: ByteArray, cn: ByteArray, sn: ByteArray) = SessionKeys(
        Crypto.mac(link, "couch-phone-c2s".toByteArray(), cn, sn),
        Crypto.mac(link, "couch-phone-s2c".toByteArray(), cn, sn),
    )
}
