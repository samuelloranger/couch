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
 *
 * A [grid] panel spans the screen with as many columns as it takes to show every item at once;
 * the D-pad moves in both directions and holding OK adjusts an item that allows it.
 */
class OverlayPanel(
    private val context: Context,
    private val hint: String,
    private val end: Boolean = false,
    private val grid: Boolean = false,
) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var view: PanelView? = null
    private var params: WindowManager.LayoutParams? = null
    private var rows: List<PanelRow> = emptyList()
    private var keys: List<String> = emptyList()
    private var shownHint = ""
    private var selected = 0
    /** OK is held on the selected item: Left and Right adjust it. */
    private var holding = false
    /** The press that started [holding]: its release mustn't also choose. */
    private var holdPress = false
    private val idle = Runnable { close() }

    /** Told the key of each item opened, so the next opening can start on it. */
    var onChosen: ((String) -> Unit)? = null

    /** Runs each time the panel closes, a good moment to rebuild it for the next opening. */
    var onClosed: (() -> Unit)? = null

    /** Runs each time the panel opens, to read live values again. */
    var onShown: (() -> Unit)? = null

    val isOpen: Boolean get() = view?.visibility == View.VISIBLE

    /** Builds the panel for [items] while it's hidden, unless it already shows the same list. */
    fun prepare(items: List<PanelItem>, hint: String = this.hint) {
        if (items.isEmpty() || isOpen || (items.map { it.key } == keys && hint == shownHint)) return
        detach()
        rows = items.map { PanelRow(context, it, compact = grid) }
        keys = items.map { it.key }
        shownHint = hint
        val columns = if (grid) PanelView.columnsFor(PanelView.sections(items.map { it.section }).map { it.count() }, safeHeightDp()) else 1
        val created = PanelView(context, rows, hint, end, columns)
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
        onShown?.invoke()
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

    operator fun contains(key: String): Boolean = key in keys

    /** The key of the selected item, for a second press to step from. */
    val current: String? get() = rows.getOrNull(selected)?.item?.key

    /** Keys of the inputs among the items, in order. */
    val inputKeys: List<String> get() = rows.filter { it.item.icon is PanelItem.Icon.Input }.map { it.item.key }

    fun close() {
        main.removeCallbacks(idle)
        endHold()
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
        if (grid) return gridKey(panel, event).also { if (it) restartIdle() }
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

    private fun gridKey(panel: PanelView, event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (down && !holding) {
                move(panel, panel.neighbour(selected, 0, if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else 1))
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) {
                val by = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                if (holding) adjust(by) else move(panel, panel.neighbour(selected, by, 0))
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> when {
                down -> if (event.repeatCount == 1 && !holding) startHold()
                holdPress -> holdPress = false
                holding -> endHold()
                else -> choose()
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> if (!down) {
                if (holding) endHold() else close()
            }
            else -> return false
        }
        return true
    }

    private fun startHold() {
        val row = rows.getOrNull(selected) ?: return
        if (row.item.hold?.invoke() != true) return
        holding = true
        holdPress = true
        row.adjusting = true
        row.refresh()
    }

    private fun endHold() {
        if (!holding) return
        holding = false
        holdPress = false
        val row = rows.getOrNull(selected) ?: return
        row.adjusting = false
        row.item.endHold()
        row.refresh()
    }

    private fun safeHeightDp(): Int {
        val metrics = context.resources.displayMetrics
        return (metrics.heightPixels / metrics.density).toInt() - 2 * Tokens.OVERSCAN_V
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
        shownHint = ""
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
