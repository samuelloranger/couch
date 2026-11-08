package cloud.samlo.couch.phone

/**
 * Splits an H.264 Annex-B byte stream, fed in arbitrary pieces, into NAL units without their start
 * codes (`00 00 01`, or `00 00 00 01`). A NAL is complete when the next start code shows up, so
 * the last one waits there until [flush] says the stream went quiet. Bounded: a NAL longer than
 * [MAX_NAL] means the stream isn't H.264 or has lost sync, and what is held is dropped.
 */
class AnnexB(private val onNal: (ByteArray) -> Unit) {
    private var buf = ByteArray(INITIAL)
    private var len = 0
    /** Where the pending NAL's bytes start; -1 while no start code has been seen. */
    private var start = -1
    private var scan = 0

    fun feed(data: ByteArray, offset: Int = 0, count: Int = data.size - offset) {
        if (len + count > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + count))
        System.arraycopy(data, offset, buf, len, count)
        len += count
        find()
        if (len - maxOf(start, 0) > MAX_NAL) reset()
    }

    /** Hands over the pending NAL as complete: the encoder wrote a frame and stopped. */
    fun flush() {
        if (start < 0) return
        emit(start, trimEnd(len))
        len = 0
        start = -1
        scan = 0
    }

    private fun find() {
        var i = maxOf(scan, 0)
        while (i + 2 < len) {
            if (buf[i + 2].toInt() > 1) {
                i += 3
            } else if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 && buf[i + 2].toInt() == 1) {
                if (start >= 0) emit(start, trimEnd(i))
                start = i + 3
                i += 3
            } else {
                i++
            }
        }
        scan = i
        compact()
    }

    /** Drops what has been handed over, keeping the pending NAL and the last two bytes a start code could straddle. */
    private fun compact() {
        val keep = if (start >= 0) start else maxOf(len - 2, 0)
        if (keep == 0) return
        System.arraycopy(buf, keep, buf, 0, len - keep)
        len -= keep
        scan = maxOf(scan - keep, 0)
        if (start >= 0) start = 0
    }

    /** Zero bytes before a start code belong to it (the four-byte form), not to the NAL. */
    private fun trimEnd(end: Int): Int {
        var e = end
        while (e > start && buf[e - 1].toInt() == 0) e--
        return e
    }

    private fun emit(from: Int, to: Int) {
        if (to > from) onNal(buf.copyOfRange(from, to))
    }

    private fun reset() {
        len = 0
        start = -1
        scan = 0
    }

    companion object {
        const val MAX_NAL = 4 * 1024 * 1024
        private const val INITIAL = 64 * 1024
    }
}
