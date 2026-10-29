package cloud.samlo.couch.home

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import cloud.samlo.couch.ui.Ambient

/**
 * Decides when the home screen goes idle. The clock restarts on every remote press (and on
 * returning home); background reloads only re-arm the timer, they never count as activity.
 */
class AmbientMode(
    private val ambient: Ambient,
    private val isResumed: () -> Boolean,
) {
    private val main = Handler(Looper.getMainLooper())
    private var idleSince = 0L

    /** Minutes of idle before ambient mode; 0 turns it off. */
    var minutes = 0
    var pictures: List<String> = emptyList()
    var weatherLine: String? = null

    private val enter = Runnable {
        if (!isResumed() || minutes <= 0) return@Runnable
        ambient.show(pictures, weatherLine)
    }

    /** Arms the timer; [activity] means the user just did something. */
    fun schedule(activity: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (activity || idleSince == 0L) idleSince = now
        main.removeCallbacks(enter)
        if (!isResumed() || minutes <= 0) return
        val due = idleSince + minutes * 60_000L
        main.postDelayed(enter, (due - now).coerceAtLeast(0L))
    }

    /** Leaving home: no timer, no overlay. */
    fun cancel() {
        main.removeCallbacks(enter)
        if (ambient.showing) ambient.hide()
    }

    /** Counts the key as activity; while ambient shows, any key only wakes the screen. */
    fun consumesKey(event: KeyEvent): Boolean {
        schedule(activity = true)
        if (!ambient.showing) return false
        if (event.action == KeyEvent.ACTION_UP) ambient.hide()
        return true
    }
}
