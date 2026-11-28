package cloud.samlo.couch.index

import cloud.samlo.couch.ui.Focus
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
import cloud.samlo.couch.ui.Type
import cloud.samlo.couch.ui.appear
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf

/**
 * The strip down the left edge: Search, Home (this screen, marked with an accent bar, not an
 * action), Inputs, and Settings alone at the bottom. Items stand at fixed positions; focus only
 * repaints their colours, so a move never triggers a layout pass.
 *
 * While Search takes the app list's place the bar stands on Search instead, and Home becomes an
 * action that closes it.
 */
@SuppressLint("ViewConstructor")
class IndexRail(
    context: Context,
    onSearch: () -> Unit,
    onInputs: () -> Unit,
    onSettings: () -> Unit,
    onHome: () -> Unit,
) : FrameLayout(context) {
    val search = item(R.drawable.ic_rail_search, R.string.search, IndexTokens.RAIL_SEARCH_Y, active = false, onClick = onSearch)
    private val home = item(R.drawable.ic_rail_home, R.string.index_home, IndexTokens.RAIL_HOME_Y, active = true, onClick = onHome)
    val inputs = item(R.drawable.ic_pill_inputs, R.string.section_inputs, IndexTokens.RAIL_INPUTS_Y, active = false, onClick = onInputs)
    val settings = item(R.drawable.ic_pill_settings, R.string.settings, IndexTokens.RAIL_SETTINGS_Y, active = false, onClick = onSettings)
    private val bar = FlatView(context).apply {
        background = GradientDrawable().apply { cornerRadius = context.dpf(1f) }
    }
    private var barColor = 0
    private var searching = false

    init {
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x09FFFFFF, 0x04FFFFFF))
        addView(FlatView(context).apply { setBackgroundColor(0x0AFFFFFF) },
            LayoutParams(context.dp(1), LayoutParams.MATCH_PARENT, Gravity.END))
        val barTop = IndexTokens.RAIL_HOME_Y + (IndexTokens.RAIL_ITEM_H - IndexTokens.RAIL_BAR_H) / 2
        addView(bar, LayoutParams(context.dp(IndexTokens.RAIL_BAR_W), context.dp(IndexTokens.RAIL_BAR_H)).apply { topMargin = context.dp(barTop) })
        listOf(search, home, inputs, settings).forEach { addView(it) }
        paintBar()
    }

    /** The accent can change in Customize after this view was built. */
    fun paintBar() {
        if (barColor == Tokens.accent) return
        barColor = Tokens.accent
        (bar.background as GradientDrawable).setColor(barColor)
    }

    /** Search has the list's place: the bar glides up to it, and Home turns into OK-to-close. */
    fun setSearch(open: Boolean) {
        if (open == searching) return
        searching = open
        home.isFocusable = open
        home.isClickable = open
        val up = -context.dpf((IndexTokens.RAIL_HOME_Y - IndexTokens.RAIL_SEARCH_Y).toFloat())
        bar.animate().cancel()
        bar.animate().translationY(if (open) up else 0f).setDuration(Tokens.GLIDE_MS).setInterpolator(Tokens.ease).start()
    }

    /** Hidden when the TV has no inputs to switch to. */
    fun setHasInputs(has: Boolean, animate: Boolean) {
        inputs.appear(has, animate)
    }

    /** The focusable item whose middle is closest to [y] (screen pixels): where ← from a row lands. */
    fun nearest(y: Int): View? = focusable().minByOrNull {
        val at = IntArray(2).also { a -> it.getLocationOnScreen(a) }
        kotlin.math.abs(at[1] + it.height / 2 - y)
    }

    /** The next item up or down from [from], or null at the end. */
    fun neighbour(from: View, down: Boolean): View? {
        val items = focusable()
        val i = items.indexOf(from)
        return items.getOrNull(if (down) i + 1 else i - 1)
    }

    /** The items the remote reaches, top to bottom: Home joins them only while it closes Search. */
    private fun focusable() = listOf(search, home, inputs, settings).filter { it.visibility == VISIBLE && it.isFocusable }

    private fun item(icon: Int, label: Int, y: Int, active: Boolean, onClick: () -> Unit): LinearLayout {
        val color = if (active) IndexTokens.railActive else IndexTokens.railIdle
        val glyph = ImageView(context).apply { setImageResource(icon); setColorFilter(color) }
        val text = IndexText(context, Type.CAPTION, 500, color).apply {
            maxLines = 1
            this.text = context.getString(label)
        }
        return object : LinearLayout(context) {
            override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
                super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
                val scale = if (gainFocus) Tokens.FOCUS_SCALE else 1f
                animate().scaleX(scale).scaleY(scale)
                    .setDuration(if (gainFocus) Tokens.FOCUS_IN_MS else Tokens.FOCUS_OUT_MS).setInterpolator(Tokens.ease).start()
                val c = if (gainFocus || active) IndexTokens.railActive else IndexTokens.railIdle
                glyph.setColorFilter(c)
                text.setTextColor(c)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            contentDescription = context.getString(label)
            setOnClickListener { onClick() }
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), Focus.tileRing(context, 8f, 0x1AFFFFFF))
                addState(intArrayOf(), android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            }
            // Home marks the screen: it takes focus only while Search is open.
            isFocusable = !active
            isClickable = !active
            addView(glyph, LayoutParams(context.dp(IndexTokens.RAIL_ICON), context.dp(IndexTokens.RAIL_ICON)))
            addView(text, text.stack(LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT), top = context.dp(4)))
            layoutParams = FrameLayout.LayoutParams(context.dp(IndexTokens.RAIL_ITEM_W), context.dp(IndexTokens.RAIL_ITEM_H)).apply {
                leftMargin = context.dp((IndexTokens.RAIL_W - IndexTokens.RAIL_ITEM_W) / 2)
                topMargin = context.dp(y)
            }
        }
    }
}
