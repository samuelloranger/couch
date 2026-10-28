package cloud.samlo.couch.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf

/**
 * One focusable option: title on the left, and on the right either its current value or a
 * [control] that shows it. Focusing the row opens [helpText] under the title; the side [panel]
 * only explains grid tiles now, so a focused row empties it.
 */
@SuppressLint("ViewConstructor")
class OptionRow(
    context: Context,
    private val helpText: String,
    private val valueOf: () -> String,
    panel: TextView,
) : LinearLayout(context) {
    val label = TextView(context).apply {
        textSize = 17f
        setTextColor(Tokens.textPrimary)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    val value = TextView(context).apply {
        textSize = 16f
        setTextColor(Tokens.textMuted)
        maxLines = 1
        gravity = Gravity.END
    }
    private val help = TextView(context).apply {
        textSize = 14f
        setTextColor(Tokens.textMuted)
        setLineSpacing(0f, 1.2f)
        visibility = GONE
    }
    private val line = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(24)
    }
    private var control: View? = null
    private var showControl: () -> Unit = {}

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        minimumHeight = context.dp(52)
        setPadding(context.dp(20), context.dp(13), context.dp(20), context.dp(13))
        background = optionBackground(context)
        line.addView(label, LayoutParams(0, WRAP_CONTENT, 1f))
        line.addView(value, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = context.dp(16) })
        addView(line, LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(help, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = context.dp(5) })
        help.text = helpText
        setOnFocusChangeListener { _, focused ->
            if (focused) panel.text = ""
            help.visibility = if (focused && helpText.isNotEmpty()) VISIBLE else GONE
            value.setTextColor(if (focused) Tokens.textPrimary else Tokens.textMuted)
        }
        refresh()
    }

    /** Shows the row's state with [view] instead of the value text; [show] brings it up to date. */
    fun <T : View> use(view: T, show: (T) -> Unit) {
        control = view
        showControl = { show(view) }
        line.addView(view, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = context.dp(16) })
        refresh()
    }

    /** Puts [text] where the value or control is, until the next [refresh]. */
    fun mark(text: String) {
        value.text = text
        value.visibility = VISIBLE
        control?.visibility = GONE
    }

    fun refresh() {
        value.text = valueOf().let { text ->
            // Roboto draws the arrow small; it is the only thing saying the row leaves Couch.
            if (!text.endsWith(LEAVES)) return@let text
            SpannableString(text).apply { setSpan(RelativeSizeSpan(1.4f), text.length - 1, text.length, 0) }
        }
        val control = control ?: return
        value.visibility = GONE
        control.visibility = VISIBLE
        showControl()
    }
}

/** Ends the value of a row that leaves settings. */
const val LEAVES = "↗"

/** Rounded highlight in the accent colour while focused; call again after the accent changes. */
fun optionBackground(context: Context) = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
        cornerRadius = context.dpf(Tokens.CARD_RADIUS)
        setColor(Tokens.surfaceFocused)
        setStroke(context.dp(2), Tokens.accent)
    })
    addState(intArrayOf(), GradientDrawable().apply { cornerRadius = context.dpf(Tokens.CARD_RADIUS) })
}
