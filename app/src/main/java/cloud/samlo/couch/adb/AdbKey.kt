package cloud.samlo.couch.adb

import android.util.Base64
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * One RSA keypair kept in Couch's own storage: this is the key the TV's "Allow debugging?" dialog
 * authorized. Uninstalling Couch deletes it, so nothing left behind can open a shell.
 */
class AdbKey private constructor(private val pair: KeyPair, private val name: String) {
    private val public = pair.public as RSAPublicKey

    /** adbd's token challenge, answered with a raw-RSA signature over SHA-1(token). */
    fun sign(token: ByteArray): ByteArray {
        val sha1Prefix = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )
        return Signature.getInstance("NONEwithRSA").run {
            initSign(pair.private)
            update(sha1Prefix + token)
            sign()
        }
    }

    /** Android's 524-byte `android_pubkey` format, base64 with a name, as adbd expects it. */
    fun adbdPublicKey(): ByteArray {
        val n = public.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toLong().toInt()
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
        val buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(64)
        buf.putInt(n0inv)
        buf.put(le(n, 256))
        buf.put(le(rr, 256))
        buf.putInt(public.publicExponent.toInt())
        val b64 = Base64.encodeToString(buf.array(), Base64.NO_WRAP)
        return "$b64 $name\u0000".toByteArray()
    }

    /** Big-endian positive integer as [size] little-endian bytes. */
    private fun le(value: BigInteger, size: Int): ByteArray {
        val be = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(size) { i -> if (i < be.size) be[be.size - 1 - i] else 0 }
    }

    companion object {
        /** Reuses the stored key, or generates one and keeps both halves on disk. */
        fun load(dir: File, name: String): AdbKey {
            val priv = File(dir, "adbkey")
            val pub = File(dir, "adbkey.pub")
            val factory = KeyFactory.getInstance("RSA")
            if (priv.isFile && pub.isFile) {
                try {
                    return AdbKey(
                        KeyPair(factory.generatePublic(X509EncodedKeySpec(pub.readBytes())), factory.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes()))),
                        name,
                    )
                } catch (e: Exception) {
                    // A broken key file is regenerating, at the cost of one more dialog.
                    priv.delete()
                    pub.delete()
                }
            }
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            priv.writeBytes(pair.private.encoded)
            pub.writeBytes(pair.public.encoded)
            return AdbKey(pair, name)
        }
    }
}
