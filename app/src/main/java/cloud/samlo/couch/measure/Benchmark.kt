package cloud.samlo.couch.measure

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import cloud.samlo.couch.data.Prefs
import org.json.JSONObject

/**
 * Godmode's benchmark: a fixed set of D-pad presses sent to Couch's own window (no permission
 * needed for one's own window), the frames that follow each press timed like the HUD times them.
 * Each layout has its own script, and a result is only compared with the last one of the same
 * layout and script, so changing the script never makes a run look better or worse.
 */
class Benchmark(private val activity: Activity) {
    class Result(val p50: Int, val p90: Int, val p99: Int, val slow: Int, val frames: Int, val presses: Int, val histogram: IntArray)

    private val meter = FrameMeter(activity.window)
    private val main = Handler(Looper.getMainLooper())
    private val frames = ArrayList<Frame>()
    private var pressesNs = LongArray(0)
    private var done: ((Result?) -> Unit)? = null

    val running: Boolean get() = done != null

    /** Runs [script] from where the home screen was reset to; [onDone] gets null when a real press stopped it. */
    fun run(index: Boolean, onDone: (Result?) -> Unit) {
        if (running) return
        val keys = if (index) INDEX_SCRIPT else ROWS_SCRIPT
        pressesNs = LongArray(keys.size)
        frames.clear()
        done = onDone
        meter.start { f -> synchronized(frames) { frames += f } }
        keys.forEachIndexed { i, code ->
            main.postDelayed({ press(i, code) }, START_MS + i * GAP_MS)
        }
        main.postDelayed({ finish() }, START_MS + keys.size * GAP_MS + Attribution.WINDOW_NS / 1_000_000)
    }

    /** A real remote press (not one of the script's) ends the run without a result. */
    fun abortOn(event: KeyEvent): Boolean {
        if (!running || event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD) return false
        cancel()
        return true
    }

    /** Stops a run (the screen paused, HOME was pressed): no more presses, no result. */
    fun cancel() {
        if (!running) return
        main.removeCallbacksAndMessages(null)
        meter.stop()
        val callback = done
        done = null
        callback?.invoke(null)
    }

    private fun press(i: Int, code: Int) {
        if (!running) return
        pressesNs[i] = System.nanoTime()
        val now = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            activity.dispatchKeyEvent(KeyEvent(now, now, action, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0))
        }
    }

    private fun finish() {
        meter.stop()
        val counted = synchronized(frames) { frames.filter { Attribution.pressFor(it, pressesNs) >= 0 } }
        val result = Result(
            Percentiles.of(counted, 50), Percentiles.of(counted, 90), Percentiles.of(counted, 99),
            counted.count { it.slow }, counted.size, pressesNs.size, histogram(counted),
        )
        val callback = done
        done = null
        callback?.invoke(result)
    }

    companion object {
        /** Bumped whenever a script changes, so old results stop being compared. */
        const val SCRIPT = 1
        private const val START_MS = 800L
        /** Slower than the 260 ms glide is long, so each press is measured on its own. */
        private const val GAP_MS = 300L
        private val R = KeyEvent.KEYCODE_DPAD_RIGHT
        private val L = KeyEvent.KEYCODE_DPAD_LEFT
        private val D = KeyEvent.KEYCODE_DPAD_DOWN
        private val U = KeyEvent.KEYCODE_DPAD_UP
        private val ROWS_SCRIPT = (List(10) { R } + D + List(10) { R } + D + List(10) { L } + U + List(10) { L } + U).let { it + it }
        private val INDEX_SCRIPT = (List(20) { D } + List(20) { U }).let { it + it }

        /** Frame times in 2 ms buckets from 0 to 32 ms; the last bucket holds everything slower. */
        fun histogram(frames: List<Frame>): IntArray {
            val buckets = IntArray(17)
            frames.forEach { buckets[(it.ms / 2).toInt().coerceIn(0, 16)]++ }
            return buckets
        }

        /** The last result for [layout] with the current script, or null. */
        fun last(activity: Activity, layout: String): JSONObject? =
            Prefs.of(activity).getString("bench:$layout", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.takeIf { it.optInt("script") == SCRIPT }

        fun save(activity: Activity, layout: String, r: Result) {
            val json = JSONObject().put("script", SCRIPT).put("p50", r.p50).put("p90", r.p90).put("p99", r.p99)
            Prefs.of(activity).edit().putString("bench:$layout", json.toString()).apply()
        }
    }
}
