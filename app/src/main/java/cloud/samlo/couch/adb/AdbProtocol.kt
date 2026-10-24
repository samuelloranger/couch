package cloud.samlo.couch.adb

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * adb's framing, spoken to the TV's own adbd: a 24-byte little-endian header, then the payload,
 * whose checksum rides along in the header.
 */
internal object AdbProtocol {
    const val CNXN = 0x4e584e43 // "CNXN"
    const val AUTH = 0x48545541 // "AUTH"
    const val OPEN = 0x4e45504f // "OPEN"
    const val OKAY = 0x59414b4f // "OKAY"
    const val CLSE = 0x45534c43 // "CLSE"
    const val WRTE = 0x45545257 // "WRTE"

    /** Version and the max payload adbd offers in the reply. */
    const val VERSION = 0x01000001
    const val MAX_PAYLOAD = 256 * 1024

    class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray) {
        fun name() = String(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(cmd).array())
    }

    fun write(out: OutputStream, cmd: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) {
        val head = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        head.putInt(cmd)
        head.putInt(arg0)
        head.putInt(arg1)
        head.putInt(data.size)
        head.putInt(data.sumOf { it.toInt() and 0xff })
        head.putInt(cmd.inv())
        out.write(head.array())
        if (data.isNotEmpty()) out.write(data)
        out.flush()
    }
}
