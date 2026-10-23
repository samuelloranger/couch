package cloud.samlo.couch.measure

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import cloud.samlo.couch.R
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf

/**
 * Godmode's frame HUD: one line per remote press, newest first, with the press's worst frame and,
 * when it ran over budget, the stage that took longest. Each frame reads as milliseconds or as a
 * rate, whichever the user picked. The panel only redraws once presses stop, after the press
 * window, so its own redraw never counts. Off, it has no view and no listener.
 */
class FrameHud(private val activity: Activity) {
    private class Press(val atNs: Long, val key: String) {
        var worst: Frame? = null
    }

    private val meter = FrameMeter(activity.window)
    private val main = Handler(Looper.getMainLooper())
    private val presses = ArrayList<Press>()
    private val counted = ArrayList<Frame>()
    private var panel: TextView? = null
    private val render = Runnable { draw() }

    private var unit = Config.HUD_OFF

    val on: Boolean get() = panel != null

    /** [Config.HUD_OFF], [Config.HUD_MS] or [Config.HUD_FPS]. */
    fun enable(unit: String) {
        val show = unit != Config.HUD_OFF
        val changed = unit != this.unit
        this.unit = unit
        if (show && on && changed) {
            draw()
            return
        }
        if (show == on) return
        if (show) attach() else detach()
    }

    /** What the screen can actually show, so a rate never claims more than the TV displays. */
    private val refreshHz: Float
        get() = activity.windowManager.defaultDisplay?.refreshRate?.takeIf { it > 1f } ?: 60f

    /** From the key path, before anything can consume the press. */
    fun onKey(event: KeyEvent) {
        if (!on || event.action != KeyEvent.ACTION_DOWN) return
        val key = KEYS[event.keyCode] ?: return
        synchronized(presses) {
            presses += Press(System.nanoTime(), key)
            while (presses.size > KEEP) presses.removeAt(0)
        }
        main.removeCallbacks(render)
        main.postDelayed(render, REDRAW_AFTER_MS)
    }

    private fun onFrame(frame: Frame) = synchronized(presses) {
        val index = Attribution.pressFor(frame, LongArray(presses.size) { presses[it].atNs })
        if (index < 0) return@synchronized
        val press = presses[index]
        if ((press.worst?.totalNs ?: -1) < frame.totalNs) press.worst = frame
        counted += frame
        while (counted.size > STATS) counted.removeAt(0)
    }

    private fun draw() {
        val view = panel ?: return
        val text = StringBuilder(activity.getString(R.string.hud_title)).append('\n')
        synchronized(presses) {
            val hz = refreshHz
            presses.asReversed().take(LINES).forEach { p ->
                val f = p.worst
                text.append(p.key).append("  ").append(value(f?.ms ?: 0f, hz)).append("  ")
                text.append(if (f != null && f.slow) "✗ " + stageName(f.stage) else "✓").append('\n')
            }
            val slow = counted.count { it.slow }
            val p90 = Percentiles.of(counted, 90)
            val footer = if (unit == Config.HUD_FPS) {
                activity.getString(R.string.hud_footer_fps, fps(p90.toFloat(), hz), slow, counted.size)
            } else {
                activity.getString(R.string.hud_footer, p90, slow, counted.size)
            }
            text.append(footer)
        }
        view.text = text
    }

    /** The frame as the chosen unit, padded so the column stays straight. */
    private fun value(ms: Float, hz: Float): String =
        if (unit == Config.HUD_FPS) activity.getString(R.string.hud_fps_value, fps(ms, hz)).padStart(7)
        else activity.getString(R.string.hud_ms_value, ms.toInt()).padStart(7)

    private fun stageName(stage: Stage) = activity.getString(
        when (stage) {
            Stage.INPUT -> R.string.stage_input
            Stage.ANIMATION -> R.string.stage_animation
            Stage.LAYOUT -> R.string.stage_layout
            Stage.DRAW -> R.string.stage_draw
            Stage.UPLOAD -> R.string.stage_upload
            Stage.GPU -> R.string.stage_gpu
        },
    )

    private fun attach() {
        val c = activity
        val view = TextView(c).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Tokens.textPrimary)
            setLineSpacing(0f, 1.15f)
            setPadding(c.dp(12), c.dp(9), c.dp(12), c.dp(9))
            background = GradientDrawable().apply {
                cornerRadius = c.dpf(10f)
                setColor(0xD1050807.toInt())
                setStroke(c.dp(1), (Tokens.GODMODE_COLOR and 0x00FFFFFF) or 0x66000000)
            }
        }
        (c.window.decorView as ViewGroup).addView(view, FrameLayout.LayoutParams(c.dp(236), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = c.dp(Tokens.OVERSCAN_V + 64)
            marginEnd = c.dp(Tokens.OVERSCAN_H)
        })
        panel = view
        meter.start(::onFrame)
        draw()
    }

    private fun detach() {
        meter.stop()
        main.removeCallbacks(render)
        panel?.let { (it.parent as? ViewGroup)?.removeView(it) }
        panel = null
        synchronized(presses) {
            presses.clear()
            counted.clear()
        }
    }

    private companion object {
        const val LINES = 5
        const val KEEP = 16
        const val STATS = 600
        const val REDRAW_AFTER_MS = 600L
        val KEYS = mapOf(
            KeyEvent.KEYCODE_DPAD_UP to "↑", KeyEvent.KEYCODE_DPAD_DOWN to "↓",
            KeyEvent.KEYCODE_DPAD_LEFT to "←", KeyEvent.KEYCODE_DPAD_RIGHT to "→",
            KeyEvent.KEYCODE_DPAD_CENTER to "●", KeyEvent.KEYCODE_ENTER to "●", KeyEvent.KEYCODE_BACK to "◁",
        )
    }
}
