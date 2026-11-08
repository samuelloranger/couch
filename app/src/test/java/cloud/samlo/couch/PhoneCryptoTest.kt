package cloud.samlo.couch

import cloud.samlo.couch.phone.FrameCipher
import cloud.samlo.couch.phone.Frames
import cloud.samlo.couch.phone.Handshake
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** The phone session's keys and frames, with fixed vectors the iOS side asserts too. */
class PhoneCryptoTest {
    private val link = ByteArray(32) { it.toByte() }
    private val cn = ByteArray(16) { it.toByte() }
    private val sn = ByteArray(16) { (it + 16).toByte() }
    private val plain = "\u0001{\"t\":\"ping\"}".toByteArray()

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun unhex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun keysAndProofsMatchTheVectors() {
        val keys = Handshake.keys(link, cn, sn)
        assertEquals("696ef403b13b52fdcf78f03c30c9b290cf5902e8b81c075464000c4a60d051ce", hex(keys.clientToServer))
        assertEquals("752b2efa19d5cd52d8ff345f3f0e03d704eedc7beee1ebe2dbc89f60b0ac0fc0", hex(keys.serverToClient))
        assertEquals("b899ace203f477d84935294866679fb4fdd558d23d0364d2c7f0892c9bf9335e", hex(Handshake.requestProof(link, cn, "phone-1")))
        assertEquals("85974a22f380e4af22b49c348e431d05676fa6241a4d85a0f528ca910a334e08", hex(Handshake.replyProof(link, cn, sn)))
    }

    @Test
    fun theFirstFrameOfEachDirectionMatchesTheVectors() {
        val keys = Handshake.keys(link, cn, sn)
        assertEquals("0a0a6b0bbb0b4529b51e65b86f44dfce87c8b49dae2ec0d91888872dc1", hex(FrameCipher(keys.clientToServer).seal(plain)))
        assertEquals("c39f28299cb0f69dfee7b8e6acee1c1ba89ffba40c26f9d2f9381edf80", hex(FrameCipher(keys.serverToClient).seal(plain)))
    }

    @Test
    fun theCounterAdvancesPerFrame() {
        val cipher = FrameCipher(Handshake.keys(link, cn, sn).serverToClient)
        cipher.seal(plain)
        assertEquals("4ae581822bffc631240d4ef352371933d47865ec20a024ff3c7aa01aaa", hex(cipher.seal(plain)))
    }

    @Test
    fun aFrameOpensOnlyInOrderWithItsOwnKey() {
        val keys = Handshake.keys(link, cn, sn)
        val first = unhex("0a0a6b0bbb0b4529b51e65b86f44dfce87c8b49dae2ec0d91888872dc1")
        assertArrayEquals(plain, FrameCipher(keys.clientToServer).open(first))
        // The same bytes a second time carry the wrong counter.
        FrameCipher(keys.clientToServer).apply {
            open(first)
            assertNull(open(first))
        }
        assertNull(FrameCipher(keys.serverToClient).open(first))
        val tampered = first.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertNull(FrameCipher(keys.clientToServer).open(tampered))
    }

    @Test
    fun framesRoundTripWithTheirLengthPrefix() {
        val keys = Handshake.keys(link, cn, sn)
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        val sealer = FrameCipher(keys.serverToClient)
        Frames.write(out, sealer, plain)
        Frames.write(out, sealer, byteArrayOf(3, 1))
        val raw = bytes.toByteArray()
        assertEquals(plain.size + 16, DataInputStream(ByteArrayInputStream(raw)).readInt())
        val input = DataInputStream(ByteArrayInputStream(raw))
        val opener = FrameCipher(keys.serverToClient)
        assertArrayEquals(plain, Frames.read(input, opener))
        assertArrayEquals(byteArrayOf(3, 1), Frames.read(input, opener))
    }

    @Test
    fun aFrameOverTheLimitOrShorterThanATagClosesTheSession() {
        val cipher = FrameCipher(ByteArray(32))
        for (size in listOf(0, 16, Frames.MAX + 1, -1)) {
            val raw = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(size) }.toByteArray()
            try {
                Frames.read(DataInputStream(ByteArrayInputStream(raw)), cipher)
                fail("size $size accepted")
            } catch (e: IOException) {
                assertNotNull(e.message)
            }
        }
    }
}
