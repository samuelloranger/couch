package cloud.samlo.couch.index

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import cloud.samlo.couch.R
import cloud.samlo.couch.ui.FlatView
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf

/**
 * The strip down the left edge: Home (this screen, marked with an accent bar, not an action),
 * Inputs, and Settings alone at the bottom. Items stand at fixed positions; focus only repaints
 * their colours, so a move never triggers a layout pass.
 */
@SuppressLint("ViewConstructor")
class IndexRail(context: Context, onInputs: () -> Unit, onSettings: () -> Unit) : FrameLayout(context) {
    private val home = item(R.drawable.ic_rail_home, R.string.index_home, IndexTokens.RAIL_HOME_Y, null)
    val inputs = item(R.drawable.ic_pill_inputs, R.string.section_inputs, IndexTokens.RAIL_INPUTS_Y, onInputs)
    val settings = item(R.drawable.ic_pill_settings, R.string.settings, IndexTokens.RAIL_SETTINGS_Y, onSettings)
    private val bar = FlatView(context).apply {
        background = GradientDrawable().apply { cornerRadius = context.dpf(1f) }
    }
    private var barColor = 0

    init {
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x09FFFFFF, 0x04FFFFFF))
        addView(FlatView(context).apply { setBackgroundColor(0x0AFFFFFF) },
            LayoutParams(context.dp(1), LayoutParams.MATCH_PARENT, Gravity.END))
        val barTop = IndexTokens.RAIL_HOME_Y + (IndexTokens.RAIL_ITEM_H - IndexTokens.RAIL_BAR_H) / 2
        addView(bar, LayoutParams(context.dp(IndexTokens.RAIL_BAR_W), context.dp(IndexTokens.RAIL_BAR_H)).apply { topMargin = context.dp(barTop) })
        listOf(home, inputs, settings).forEach { addView(it) }
        paintBar()
    }

    /** The accent can change in Customize after this view was built. */
    fun paintBar() {
        if (barColor == Tokens.accent) return
        barColor = Tokens.accent
        (bar.background as GradientDrawable).setColor(barColor)
    }

    /** Hidden when the TV has no inputs to switch to. */
    fun setHasInputs(has: Boolean) {
        inputs.visibility = if (has) View.VISIBLE else View.GONE
    }

    /** The focusable item whose middle is closest to [y] (screen pixels): where ← from a row lands. */
    fun nearest(y: Int): View? = listOf(inputs, settings).filter { it.visibility == VISIBLE }.minByOrNull {
        val at = IntArray(2).also { a -> it.getLocationOnScreen(a) }
        kotlin.math.abs(at[1] + it.height / 2 - y)
    }

    /** The next item up or down from [from], or null at the end. */
    fun neighbour(from: View, down: Boolean): View? {
        val items = listOf(inputs, settings).filter { it.visibility == VISIBLE }
        val i = items.indexOf(from)
        return items.getOrNull(if (down) i + 1 else i - 1)
    }

    private fun item(icon: Int, label: Int, y: Int, onClick: (() -> Unit)?): LinearLayout {
        val active = onClick == null
        val color = if (active) IndexTokens.railActive else IndexTokens.railIdle
        val glyph = ImageView(context).apply { setImageResource(icon); setColorFilter(color) }
        val text = IndexText(context, 7.5f, 500, color).apply {
            maxLines = 1
            this.text = context.getString(label)
        }
        return object : LinearLayout(context) {
            override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
                super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
                val c = if (gainFocus || active) IndexTokens.railActive else IndexTokens.railIdle
                glyph.setColorFilter(c)
                text.setTextColor(c)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            contentDescription = context.getString(label)
            if (onClick != null) {
                isFocusable = true
                isClickable = true
                setOnClickListener { onClick() }
                background = StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply {
                        cornerRadius = context.dpf(8f)
                        setColor(0x1AFFFFFF)
                        setStroke(context.dp(2), RING)
                    })
                    addState(intArrayOf(), android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                }
            }
            addView(glyph, LayoutParams(context.dp(IndexTokens.RAIL_ICON), context.dp(IndexTokens.RAIL_ICON)))
            addView(text, text.stack(LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT), top = context.dp(4)))
            layoutParams = FrameLayout.LayoutParams(context.dp(IndexTokens.RAIL_ITEM_W), context.dp(IndexTokens.RAIL_ITEM_H)).apply {
                leftMargin = context.dp((IndexTokens.RAIL_W - IndexTokens.RAIL_ITEM_W) / 2)
                topMargin = context.dp(y)
            }
        }
    }

    private companion object {
        /** Tokens.ring, a little quieter than on the pills: the rail stands all the time. */
        val RING = 0x99FFFFFF.toInt()
    }
}
