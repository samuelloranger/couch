package cloud.samlo.couch.measure

/** Which part of a frame took longest: what a slow frame's line in the HUD names. */
enum class Stage { INPUT, ANIMATION, LAYOUT, DRAW, UPLOAD, GPU }

/** One drawn frame: when it was meant to start, how long it took, and its slowest stage. */
class Frame(val startNs: Long, val totalNs: Long, val stage: Stage) {
    val ms: Float get() = totalNs / 1_000_000f
    val slow: Boolean get() = totalNs > BUDGET_NS

    companion object {
        /** One refresh at 60 Hz. */
        const val BUDGET_NS = 16_666_667L
    }
}

/**
 * Ties frames to the remote press that caused them: a frame starting within [WINDOW_NS] of a press
 * belongs to it. Frames nobody asked for (the weather icons' constant animation, the HUD's own
 * redraw after presses stop) belong to no press, so they never count.
 */
object Attribution {
    const val WINDOW_NS = 450_000_000L

    /** The press [frame] belongs to: the latest one at or before it, inside the window; -1 for none. */
    fun pressFor(frame: Frame, pressesNs: LongArray): Int {
        var found = -1
        for (i in pressesNs.indices) {
            val since = frame.startNs - pressesNs[i]
            if (since in 0..WINDOW_NS) found = i
        }
        return found
    }
}

/**
 * A frame's duration as a rate, rounded, never above what the screen can show: a 5 ms frame on a
 * 60 Hz TV still appears 60 times a second, so claiming 200 fps would be made up.
 */
fun fps(ms: Float, refreshHz: Float): Int = Math.round(minOf(1000f / ms.coerceAtLeast(0.1f), refreshHz))

/** Percentiles in milliseconds, nearest-rank, from frame durations. */
object Percentiles {
    fun of(frames: List<Frame>, p: Int): Int {
        if (frames.isEmpty()) return 0
        val sorted = frames.map { it.totalNs }.sorted()
        val rank = ((p / 100.0) * sorted.size).let { kotlin.math.ceil(it).toInt() }.coerceIn(1, sorted.size)
        return Math.round(sorted[rank - 1] / 1_000_000.0).toInt()
    }
}
