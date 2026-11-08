package cloud.samlo.couch.phone

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One direction of a phone session: AES-256-GCM with an implicit IV, four zero bytes and a
 * big-endian frame counter that starts at 0, so no IV travels and a replayed or dropped frame fails
 * to open. Not thread safe: one writer and one reader each own their own.
 */
class FrameCipher(key: ByteArray) {
    private val spec = SecretKeySpec(key, "AES")
    private var counter = 0L

    private fun iv(): ByteArray {
        val iv = ByteArray(IV_BYTES)
        for (i in 0 until 8) iv[IV_BYTES - 1 - i] = (counter ushr (8 * i)).toByte()
        counter++
        return iv
    }

    /** Ciphertext followed by the 16-byte tag. */
    fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(TAG_BITS, iv()))
        return cipher.doFinal(plain)
    }

    /** Null when the frame was altered, replayed, out of order or sealed with another key. */
    fun open(sealed: ByteArray): ByteArray? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(TAG_BITS, iv()))
        cipher.doFinal(sealed)
    } catch (e: java.security.GeneralSecurityException) {
        null
    }

    private companion object {
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** Length-prefixed sealed frames on a stream: `uint32 BE length | ciphertext || tag`. */
object Frames {
    /** The largest frame either side accepts; more closes the session. */
    const val MAX = 4 * 1024 * 1024
    private const val TAG_BYTES = 16

    fun write(out: DataOutputStream, cipher: FrameCipher, plain: ByteArray) {
        val sealed = cipher.seal(plain)
        out.writeInt(sealed.size)
        out.write(sealed)
        out.flush()
    }

    /** Throws [IOException] for a closed stream, a size out of range or a frame that doesn't open. */
    fun read(input: DataInputStream, cipher: FrameCipher): ByteArray {
        val size = input.readInt()
        if (size !in TAG_BYTES + 1..MAX) throw IOException("frame of $size bytes")
        val sealed = ByteArray(size).also(input::readFully)
        return cipher.open(sealed) ?: throw IOException("frame didn't open")
    }
}
