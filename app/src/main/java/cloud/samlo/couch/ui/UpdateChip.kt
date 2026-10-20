package cloud.samlo.couch.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView
import cloud.samlo.couch.R

/** "Update 0.3.0" in the top bar, left of the weather. Hidden until a newer release exists. */
class UpdateChip(context: Context) : TextView(context) {
    init {
        textSize = 14f
        gravity = Gravity.CENTER
        isFocusable = true
        visibility = View.GONE
        val padH = context.dp(16)
        setPadding(padH, 0, padH, 0)
    }

    /** Null hides the chip. Recoloured each time so it follows the accent. */
    fun show(version: String?) {
        if (version == null) {
            fadeOut()
            return
        }
        text = context.getString(R.string.update_chip, version)
        setTextColor(Tokens.accent)
        background = accentChip(context)
        fadeIn()
    }

    /** While the APK downloads. The chip stays focusable, so focus doesn't jump away. */
    fun downloading() {
        text = context.getString(R.string.update_downloading)
    }

}

/** The top bar's accent pill (update, input offers); built on each show so it follows the accent. */
fun accentChip(context: Context) = StateListDrawable().apply {
    val pill = { fill: Int, stroke: Int ->
        GradientDrawable().apply {
            cornerRadius = context.dp(20).toFloat()
            setColor(fill)
            setStroke(context.dp(2), stroke)
        }
    }
    addState(intArrayOf(android.R.attr.state_focused), pill(0x33FFFFFF, Tokens.ring))
    addState(intArrayOf(), pill((Tokens.accent and 0x00FFFFFF) or 0x26000000, Tokens.accent))
}
