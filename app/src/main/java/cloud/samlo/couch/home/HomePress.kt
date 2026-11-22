package cloud.samlo.couch.home

/**
 * Whether a HOME press found the home screen already in front. Android pauses a resumed activity
 * to hand it a new intent and resumes it right after, so the intent never arrives while resumed:
 * a pause this recent is that hand-off, not a stay in another app.
 */
internal object HomePress {
    /** The hand-off takes a few milliseconds; coming back from an app takes far longer than this. */
    const val HAND_OFF_MS = 500L

    fun whileHome(resumed: Boolean, pausedAt: Long, now: Long): Boolean =
        resumed || (pausedAt > 0 && now - pausedAt in 0 until HAND_OFF_MS)
}
