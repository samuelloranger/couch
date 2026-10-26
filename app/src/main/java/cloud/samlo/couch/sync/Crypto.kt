package cloud.samlo.couch.sync

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The few primitives pairing and syncing need, all from the platform: P-256 key agreement, a short
 * code both TVs can compare, and AES-GCM for the snapshot. Two TVs agree on a key without a
 * typed secret; the person pairing them is the check, by comparing [sas] on both screens.
 */
object Crypto {
    private val random = SecureRandom()
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    class KeyPair internal constructor(val publicKey: ByteArray, internal val privateKey: PrivateKey)

    fun random(bytes: Int) = ByteArray(bytes).also(random::nextBytes)

    fun keyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val pair = generator.generateKeyPair()
        return KeyPair(pair.public.encoded, pair.private)
    }

    /** The secret both sides compute; throws on a public key that isn't a valid P-256 point. */
    fun agree(mine: KeyPair, theirPublic: ByteArray): ByteArray {
        val theirs = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(theirPublic))
        return KeyAgreement.getInstance("ECDH").run {
            init(mine.privateKey)
            doPhase(theirs, true)
            generateSecret()
        }
    }

    /** What the pairing TV promises before it learns the other's key, so a middle man can't pick one to match. */
    fun commit(publicKey: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(publicKey)

    /** HMAC over [parts], each length-prefixed so two splits of the same bytes never collide. */
    fun mac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        parts.forEach {
            mac.update(byteArrayOf((it.size shr 8).toByte(), it.size.toByte()))
            mac.update(it)
        }
        return mac.doFinal()
    }

    /** Six digits both screens show; a middle man ends up with a different pair of secrets, so a different code each side. */
    fun sas(secret: ByteArray, initiator: ByteArray, responder: ByteArray): String {
        val m = mac(secret, "couch-sas".toByteArray(), initiator, responder)
        val n = m.take(4).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        return "%06d".format(n % 1_000_000)
    }

    /** The long-lived key two paired TVs keep. */
    fun linkKey(secret: ByteArray, initiator: ByteArray, responder: ByteArray): ByteArray =
        mac(secret, "couch-key".toByteArray(), initiator, responder)

    /** Shows the asker holds the key, so an id seen on the network can't make this TV do the work of an answer. */
    fun requestProof(link: ByteArray, nonce: ByteArray, id: String): ByteArray =
        mac(link, "couch-request".toByteArray(), nonce, id.toByteArray())

    private fun snapshotKey(link: ByteArray) = mac(link, "couch-snapshot".toByteArray())

    /** Encrypts [plain] for the peer holding [link]; [aad] ties the answer to the request it answers. */
    fun seal(link: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val iv = random(IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(snapshotKey(link), "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(aad)
        return iv + cipher.doFinal(plain)
    }

    /** Null when the key, the request or the bytes are wrong. */
    fun open(link: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray? {
        if (sealed.size <= IV_BYTES) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(snapshotKey(link), "AES"), GCMParameterSpec(TAG_BITS, sealed.copyOfRange(0, IV_BYTES)))
            cipher.updateAAD(aad)
            cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
        } catch (e: java.security.GeneralSecurityException) {
            null
        }
    }
}
