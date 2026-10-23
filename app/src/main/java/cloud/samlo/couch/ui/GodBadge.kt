package cloud.samlo.couch.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.TextView

/**
 * The small `GOD` tag by the clock while godmode is changing something (the HUD is on, or a TV
 * setting differs from how the TV came), so nobody forgets it. Hidden otherwise.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class GodBadge(context: Context, textSizeSp: Float) : TextView(context) {
    init {
        text = "GOD"
        textSize = textSizeSp
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.08f
        gravity = Gravity.CENTER
        setTextColor(Tokens.GODMODE_COLOR)
        setPadding(context.dp(7), context.dp(2), context.dp(7), context.dp(2))
        background = GradientDrawable().apply {
            cornerRadius = context.dpf(5f)
            setColor((Tokens.GODMODE_COLOR and 0x00FFFFFF) or 0x14000000)
            setStroke(context.dp(1), Tokens.GODMODE_COLOR)
        }
        visibility = GONE
    }

    fun show(on: Boolean) {
        visibility = if (on) VISIBLE else GONE
    }
}
