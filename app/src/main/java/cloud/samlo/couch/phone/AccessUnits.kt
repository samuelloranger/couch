package cloud.samlo.couch.phone

import java.io.ByteArrayOutputStream

/** One video frame: its NAL units (no parameter sets) and whether it can be decoded alone. */
class AccessUnit(val key: Boolean, val nals: List<ByteArray>) {
    val size: Int get() = nals.sumOf { it.size + 4 }

    /** The `0x03` frame body: type, flags (bit 0 = keyframe), then each NAL as `uint32 BE length | nal`. */
    fun frame(): ByteArray {
        val out = ByteArrayOutputStream(size + 2)
        out.write(Wire.VIDEO_AU)
        out.write(if (key) 1 else 0)
        nals.forEach { nal ->
            out.write(nal.size ushr 24)
            out.write(nal.size ushr 16)
            out.write(nal.size ushr 8)
            out.write(nal.size)
            out.write(nal, 0, nal.size)
        }
        return out.toByteArray()
    }
}

/** Frame type bytes of a decrypted phone session frame. */
object Wire {
    const val JSON = 0x01
    const val VIDEO_CONFIG = 0x02
    const val VIDEO_AU = 0x03

    /** The `0x02` frame body: `uint16 BE spsLen | sps | uint16 BE ppsLen | pps`. */
    fun config(sps: ByteArray, pps: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(sps.size + pps.size + 5)
        out.write(VIDEO_CONFIG)
        listOf(sps, pps).forEach {
            out.write(it.size ushr 8)
            out.write(it.size)
            out.write(it, 0, it.size)
        }
        return out.toByteArray()
    }
}

/**
 * Groups NAL units into access units. A new one starts at a slice that begins a picture (type 1 or
 * 5 with `first_mb_in_slice` 0, which shows as the top bit of the byte after the header), and at
 * SPS, PPS, AUD or SEI. Parameter sets are not part of a frame: they go to [onConfig] whenever
 * SPS or PPS change, once both are known, because the decoder is built from them.
 */
class AccessUnits(private val onUnit: (AccessUnit) -> Unit, private val onConfig: (ByteArray, ByteArray) -> Unit) {
    private var current = ArrayList<ByteArray>()
    private var key = false
    private var hasPicture = false
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    fun add(nal: ByteArray) {
        if (nal.isEmpty()) return
        when (val type = nal[0].toInt() and 0x1F) {
            7 -> { boundary(); parameter(nal, true) }
            8 -> { boundary(); parameter(nal, false) }
            9 -> boundary()
            6 -> { if (hasPicture) boundary() }
            1, 5 -> {
                if (nal.size > 1 && (nal[1].toInt() and 0x80) != 0) boundary()
                current.add(nal)
                hasPicture = true
                if (type == 5) key = true
            }
            // Slice data partitions and the like are not something screenrecord produces.
            else -> Unit
        }
    }

    /** The stream went quiet: the frame being built is whole. */
    fun flush() = boundary()

    private fun boundary() {
        if (hasPicture) onUnit(AccessUnit(key, current))
        current = ArrayList()
        key = false
        hasPicture = false
    }

    private fun parameter(nal: ByteArray, isSps: Boolean) {
        val changed = if (isSps) !nal.contentEquals(sps) else !nal.contentEquals(pps)
        if (isSps) sps = nal else pps = nal
        val s = sps
        val p = pps
        if (changed && s != null && p != null) onConfig(s, p)
    }
}

/**
 * The frames since the last keyframe, for a phone that joins mid-stream: it starts from that
 * keyframe and catches up. Bounded: a run past [limit] bytes is dropped, and nothing is kept until
 * the next keyframe.
 */
class GopCache(private val limit: Int = 4 * 1024 * 1024) {
    private val units = ArrayList<AccessUnit>()
    private var bytes = 0
    private var open = false

    @Synchronized fun add(unit: AccessUnit) {
        if (unit.key) {
            units.clear()
            bytes = 0
            open = true
        }
        if (!open) return
        units.add(unit)
        bytes += unit.size
        if (bytes > limit) clear()
    }

    @Synchronized fun clear() {
        units.clear()
        bytes = 0
        open = false
    }

    @Synchronized fun replay(): List<AccessUnit> = units.toList()
}
