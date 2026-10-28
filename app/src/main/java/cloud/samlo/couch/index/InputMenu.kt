package cloud.samlo.couch.index

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import cloud.samlo.couch.R
import cloud.samlo.couch.data.Prefs
import cloud.samlo.couch.data.Tile
import cloud.samlo.couch.ui.FlatView
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf

/**
 * The Inputs pill's menu: the TV's inputs in a panel hanging under the pill, set like the app
 * list. Up and down glide the selection, OK switches, Back closes. A dialog window, so it takes
 * the remote while open and hands focus back to the pill when it closes. It opens on the input
 * chosen last, the best guess at what the TV is showing. It doesn't scroll: about eight inputs fit
 * under the pill, more than a TV usually has.
 */
@SuppressLint("RtlHardcoded")
class InputMenu(private val activity: Activity, private val inputs: List<Tile>, anchor: View) : Dialog(activity) {
    private val rows = inputs.map { InputMenuRow(activity, it) }
    private val marker = GradientDrawable().apply {
        cornerRadius = activity.dpf(1.5f)
        setColor(Tokens.accent)
    }
    private val selection = FrameLayout(activity).apply {
        background = GradientDrawable().apply {
            cornerRadius = activity.dpf(6f)
            setColor(Tokens.surfaceFocused)
        }
        addView(FlatView(activity).apply { background = marker }, FrameLayout.LayoutParams(activity.dp(3), activity.dp(IndexTokens.ROW_H - 22), Gravity.CENTER_VERTICAL))
    }
    private val panel = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = activity.dpf(PANEL_RADIUS)
            setColor(Tokens.surface)
        }
        elevation = activity.dpf(12f)
        setPadding(activity.dp(10), activity.dp(14), activity.dp(10), activity.dp(12))
    }
    private var selected = 0

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val title = IndexText(activity, 15f, 600, Tokens.textMuted).apply { setText(R.string.section_inputs) }
        panel.addView(title, title.stack(LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = activity.dp(15)
        }, bottom = activity.dp(10)))
        val list = FrameLayout(activity)
        list.addView(selection, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, activity.dp(IndexTokens.ROW_H)))
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        rows.forEach { column.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.dp(IndexTokens.ROW_H)).apply { bottomMargin = activity.dp(IndexTokens.ROW_PITCH - IndexTokens.ROW_H) }) }
        list.addView(column)
        panel.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val hint = IndexText(activity, 11f, 500, IndexTokens.hours).apply { setText(R.string.input_menu_hint) }
        panel.addView(hint, hint.stack(LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginStart = activity.dp(15)
        }, top = activity.dp(8)))
        // The panel's own size, plus room for its shadow.
        val frame = FrameLayout(activity).apply { setPadding(activity.dp(SHADOW), activity.dp(SHADOW), activity.dp(SHADOW), activity.dp(SHADOW)) }
        // Some dialog themes impose a minimum width; the panel must still sit under the pill.
        frame.addView(panel, FrameLayout.LayoutParams(activity.dp(PANEL_W), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.RIGHT))
        setContentView(frame)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setDimAmount(DIM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // Right edges lined up with the pill's, just under it. RIGHT, not END: the Index canvas
            // places the pill at a fixed spot, so the panel must not mirror in a right-to-left language.
            val at = IntArray(2).also { anchor.getLocationInWindow(it) }
            setGravity(Gravity.TOP or Gravity.RIGHT)
            attributes = attributes.apply {
                x = activity.window.decorView.width - (at[0] + anchor.width) - activity.dp(SHADOW)
                y = at[1] + anchor.height + activity.dp(GAP) - activity.dp(SHADOW)
                windowAnimations = 0
            }
        }
        val last = Prefs.of(activity).getString(LAST, null)
        selected = inputs.indexOfFirst { it.key == last }.coerceAtLeast(0)
        paint(animate = false)
        setOnShowListener {
            panel.alpha = 0f
            panel.translationY = -activity.dpf(SLIDE_DP)
            panel.animate().alpha(1f).translationY(0f).setDuration(Tokens.APPEAR_MS).setInterpolator(Tokens.ease).start()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (down) move(selected - 1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) move(selected + 1)
            // On release, so the press's other half doesn't reach the input's screen.
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (!down) choose()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> Unit
            // Back closes; volume and the rest go on as usual.
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    private fun move(index: Int) {
        if (index !in rows.indices || index == selected) return
        selected = index
        paint(animate = true)
    }

    private fun paint(animate: Boolean) {
        rows.forEachIndexed { i, row -> row.chosen = i == selected }
        val y = (selected * activity.dp(IndexTokens.ROW_PITCH)).toFloat()
        if (animate) {
            selection.animate().translationY(y).setDuration(Tokens.GLIDE_MS).setInterpolator(Tokens.ease).start()
        } else {
            selection.translationY = y
        }
    }

    private fun choose() {
        val tile = inputs.getOrNull(selected) ?: return
        Prefs.of(activity).edit().putString(LAST, tile.key).apply()
        dismiss()
        tile.launch(activity)
    }

    private companion object {
        const val LAST = "inputs:last"
        const val PANEL_W = 330
        const val PANEL_RADIUS = 12f
        /** Between the pill and the panel. */
        const val GAP = 9
        /** Transparent margin left for the panel's shadow. */
        const val SHADOW = 16
        const val SLIDE_DP = 8f
        const val DIM = 0.45f
    }
}
