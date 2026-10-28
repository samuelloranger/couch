package cloud.samlo.couch.remote

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dpf

/**
 * A list a remote button opens over whatever is playing. It is an accessibility overlay that takes
 * the remote's focus while open; the app or HDMI picture underneath keeps running.
 *
 * Building a long list takes longer than a frame, which used to eat most of the opening fade. So
 * the panel is built and attached ahead of time, hidden, by [prepare]; [show] only reveals it,
 * and starts the fade on the first frame actually drawn.
 */
class OverlayPanel(private val context: Context, private val hint: String, private val end: Boolean = false) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: PanelView? = null
    private var params: WindowManager.LayoutParams? = null
    private var rows: List<PanelRow> = emptyList()
    private var keys: List<String> = emptyList()
    private var selected = 0
    private val idle = Runnable { close() }

    /** Told the key of each item opened, so the next opening can start on it. */
    var onChosen: ((String) -> Unit)? = null

    /** Runs each time the panel closes, a good moment to rebuild it for the next opening. */
    var onClosed: (() -> Unit)? = null

    val isOpen: Boolean get() = view?.visibility == View.VISIBLE

    /** Builds the panel for [items] while it's hidden, unless it already shows the same list. */
    fun prepare(items: List<PanelItem>) {
        if (items.isEmpty() || isOpen || items.map { it.key } == keys) return
        detach()
        rows = items.map { PanelRow(context, it) }
        keys = items.map { it.key }
        val created = PanelView(context, rows, hint, end)
        created.onKey = ::onKey
        created.visibility = View.GONE
        val p = params()
        windows.addView(created, p)
        view = created
        params = p
    }

    /** Opens on the item with [key] (the first one when none matches); already open, moves there. */
    fun show(key: String?) {
        val panel = view ?: return
        val index = rows.indexOfFirst { it.item.key == key }.coerceAtLeast(0)
        if (isOpen) {
            move(panel, index)
            restartIdle()
            return
        }
        rows.forEach { it.refresh() }
        selected = index
        panel.select(index)
        focusable(true)
        panel.alpha = 0f
        panel.content.translationX = context.dpf(if (end) SLIDE_DP else -SLIDE_DP)
        panel.visibility = View.VISIBLE
        panel.requestFocus()
        panel.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                panel.viewTreeObserver.removeOnPreDrawListener(this)
                panel.animate().alpha(1f).setDuration(Tokens.APPEAR_MS).setInterpolator(Tokens.ease).start()
                panel.content.animate().translationX(0f).setDuration(Tokens.APPEAR_MS).setInterpolator(Tokens.ease).start()
                return true
            }
        })
        restartIdle()
    }

    /** The key of the selected item, for a second press to step from. */
    val current: String? get() = rows.getOrNull(selected)?.item?.key

    /** Keys of the inputs among the items, in order. */
    val inputKeys: List<String> get() = rows.filter { it.item.icon is PanelItem.Icon.Input }.map { it.item.key }

    fun close() {
        main.removeCallbacks(idle)
        val panel = view ?: return
        if (!isOpen) return
        focusable(false)
        panel.animate().alpha(0f).setDuration(Tokens.FOCUS_OUT_MS).setInterpolator(Tokens.ease).withEndAction {
            if (panel.alpha == 0f) panel.visibility = View.GONE
            onClosed?.invoke()
        }.start()
    }

    /** Removes the window for good, when the service goes away. */
    fun release() {
        main.removeCallbacks(idle)
        detach()
    }

    private fun onKey(event: KeyEvent): Boolean {
        val panel = view ?: return false
        if (!isOpen) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (down) move(panel, selected - 1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) move(panel, selected + 1)
            // Acting on release keeps the press's other half from reaching the app that opens.
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (!down) choose()
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> if (!down) close()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) adjust(if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
            else -> return false
        }
        restartIdle()
        return true
    }

    private fun move(panel: PanelView, index: Int) {
        if (index !in rows.indices) return
        selected = index
        panel.select(index)
    }

    /** Redraws every row's value, after the values were read again. */
    fun refreshRows() = rows.forEach { it.refresh() }

    private fun adjust(by: Int) {
        val row = rows.getOrNull(selected) ?: return
        val adjust = row.item.adjust ?: return
        adjust(context, by)
        row.refresh()
    }

    private fun choose() {
        val row = rows.getOrNull(selected) ?: return
        if (row.item.keepOpen || row.item.adjust != null) {
            row.item.action(context)
            row.refresh()
            return
        }
        close()
        onChosen?.invoke(row.item.key)
        row.item.action(context)
    }

    private fun detach() {
        val panel = view ?: return
        view = null
        params = null
        keys = emptyList()
        if (panel.isAttachedToWindow) windows.removeView(panel)
    }

    private fun focusable(on: Boolean) {
        val panel = view ?: return
        val p = params ?: return
        val flags = if (on) p.flags and NOT_FOCUSABLE.inv() else p.flags or NOT_FOCUSABLE
        if (flags == p.flags) return
        p.flags = flags
        windows.updateViewLayout(panel, p)
    }

    private fun restartIdle() {
        main.removeCallbacks(idle)
        main.postDelayed(idle, IDLE_MS)
    }

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    )

    private companion object {
        const val NOT_FOCUSABLE = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        /** Closes on its own like the TV's own menus when left alone. */
        const val IDLE_MS = 8_000L
        const val SLIDE_DP = 16f
    }
}
