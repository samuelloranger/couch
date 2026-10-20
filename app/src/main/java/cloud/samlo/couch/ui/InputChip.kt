package cloud.samlo.couch.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.TextView
import cloud.samlo.couch.R
import cloud.samlo.couch.data.Tile

/**
 * "Switch to PlayStation" in the top bar, offered when a device on an input turns on. It never
 * moves focus to itself: the TV may already have switched, and a press elsewhere must not be
 * stolen. Up reaches it like the other chips.
 * It goes away on its own after a while, once used, or when Couch leaves the screen.
 */
class InputChip(context: Context) : TextView(context) {
    private var tile: Tile? = null
    private val expire = Runnable { hide() }

    init {
        textSize = 14f
        gravity = Gravity.CENTER
        isFocusable = true
        visibility = View.GONE
        val padH = context.dp(16)
        setPadding(padH, 0, padH, 0)
        setOnClickListener {
            tile?.launch(context)
            hide()
        }
    }

    fun offer(input: Tile) {
        tile = input
        text = context.getString(R.string.input_offer, input.label)
        setTextColor(Tokens.accent)
        background = accentChip(context)
        fadeIn()
        removeCallbacks(expire)
        postDelayed(expire, SHOWN_MS)
    }

    fun hide() {
        removeCallbacks(expire)
        tile = null
        if (hasFocus()) focusSearch(View.FOCUS_DOWN)?.requestFocus()
        fadeOut()
    }

    private companion object {
        const val SHOWN_MS = 30_000L
    }
}
