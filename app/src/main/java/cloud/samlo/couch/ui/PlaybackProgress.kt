package cloud.samlo.couch.ui

import java.util.Locale

/**
 * The Now Playing dock's progress: where a session is now and how much of its track it has
 * played. Kept off the media APIs so the arithmetic can be checked without a session.
 */
object PlaybackProgress {
    /**
     * The position at [now], in the clock the session reported [updatedAt] in: while playing,
     * time has moved on by at least [speed] times the elapsed milliseconds.
     */
    fun position(position: Long, updatedAt: Long, speed: Float, playing: Boolean, now: Long): Long {
        val start = position.coerceAtLeast(0L)
        if (!playing || updatedAt <= 0L || now <= updatedAt) return start
        val advanced = (now - updatedAt) * speed.coerceAtLeast(0f)
        return start + advanced.toLong()
    }

    /** The share of [duration] played, 0..1; nothing played when the duration is unknown. */
    fun fraction(position: Long, duration: Long): Float =
        if (duration <= 0L) 0f else (position.toFloat() / duration).coerceIn(0f, 1f)

    /** "3:12", or "1:02:03" past an hour, as the dock's time reads. */
    fun clock(ms: Long): String {
        val seconds = ms.coerceAtLeast(0L) / 1000L
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val rest = seconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, rest)
        }
    }
}
