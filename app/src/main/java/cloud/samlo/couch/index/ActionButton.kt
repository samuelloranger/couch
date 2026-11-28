package cloud.samlo.couch.index

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.KeyEvent
import cloud.samlo.couch.ui.Focus
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.Type
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf

/**
 * A text button in the Index's right column: the one thing worth doing on a sheet ([filled], the accent
 * at its strongest) or a quiet outline for a request such as usage access. Focus is the card
 * language: a white ring, the accent glow and [Focus.scale], all render properties.
 */
@SuppressLint("ViewConstructor")
class ActionButton(context: Context, private val filled: Boolean, private val radiusDp: Float = RADIUS) :
    IndexText(context, if (filled) Type.BODY else Type.LABEL, 600, Tokens.textPrimary) {
    private var painted = 0
    private var scale = 1f

    init {
        isFocusable = true
        isClickable = true
        gravity = Gravity.CENTER
        maxLines = 1
        setPadding(context.dp(PAD), 0, context.dp(PAD), 0)
        setOnFocusChangeListener { _, has -> scaleTo(if (has) scale else 1f, if (has) Tokens.FOCUS_IN_MS else Tokens.FOCUS_OUT_MS) }
        restyle()
    }

    /** Sizes the button for its focus scale ([widthDp] wide); call once the width is known. */
    fun sizedFor(widthDp: Int) = apply { scale = Focus.scale(widthDp) }

    /** Draws the button again when the accent changed since it was last drawn. */
    fun restyle() {
        if (painted == Tokens.accent) return
        painted = Tokens.accent
        val fill = if (filled) Tokens.accent else Tokens.surfaceFocused
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), Focus.tileRing(context, radiusDp, fill))
            addState(intArrayOf(), GradientDrawable().apply {
                cornerRadius = context.dpf(radiusDp)
                if (filled) setColor(Tokens.accent) else setStroke(context.dp(1), OUTLINE)
            })
        }
        setTextColor(if (filled) onAccent(Tokens.accent) else Tokens.textPrimary)
    }

    private fun scaleTo(to: Float, ms: Long) {
        animate().scaleX(to).scaleY(to).setDuration(ms).setInterpolator(Tokens.ease).start()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (confirm(keyCode) && event.repeatCount == 0) scaleTo(Tokens.PRESS_SCALE, Tokens.PRESS_MS)
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (confirm(keyCode) && hasFocus()) scaleTo(scale, Tokens.FOCUS_IN_MS)
        return super.onKeyUp(keyCode, event)
    }

    private fun confirm(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    private companion object {
        const val RADIUS = 8f
        const val PAD = 14
        val OUTLINE = Color.parseColor("#38FFFFFF")

        /** Dark text on a light accent, light on a dark one. */
        fun onAccent(accent: Int): Int {
            val luma = 0.2126 * Color.red(accent) + 0.7152 * Color.green(accent) + 0.0722 * Color.blue(accent)
            return if (luma > 140) Tokens.background else Color.WHITE
        }
    }
}
