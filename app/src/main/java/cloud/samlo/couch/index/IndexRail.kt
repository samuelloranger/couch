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
 * The strip down the left edge: Search, Home, Inputs, and Settings alone at the bottom. The current
 * page (Home, or Search while it has the list's place) carries an accent bar and an accent icon;
 * focus is the ring, so the two never share a look. Every item takes focus, Home included: Left
 * from the list lands on the current item, and OK on Home returns to the list. Items stand at
 * fixed positions; focus only repaints, so a move never triggers a layout pass.
 */
@SuppressLint("ViewConstructor")
class IndexRail(
    context: Context,
    onSearch: () -> Unit,
    onInputs: () -> Unit,
    onSettings: () -> Unit,
    onHome: () -> Unit,
) : FrameLayout(context) {
    private val searchItem = item(R.drawable.ic_rail_search, R.string.search, IndexTokens.RAIL_SEARCH_Y, onClick = onSearch)
    private val homeItem = item(R.drawable.ic_rail_home, R.string.index_home, IndexTokens.RAIL_HOME_Y, onClick = onHome).apply { current = true }
    private val inputsItem = item(R.drawable.ic_pill_inputs, R.string.section_inputs, IndexTokens.RAIL_INPUTS_Y, onClick = onInputs)
    private val settingsItem = item(R.drawable.ic_pill_settings, R.string.settings, IndexTokens.RAIL_SETTINGS_Y, onClick = onSettings)
    val search: View get() = searchItem
    val home: View get() = homeItem
    val inputs: View get() = inputsItem
    val settings: View get() = settingsItem
    private val bar = FlatView(context).apply {
        background = GradientDrawable().apply { cornerRadius = context.dpf(1.5f) }
    }
    private var barColor = 0
    private var searching = false

    init {
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x09FFFFFF, 0x04FFFFFF))
        addView(FlatView(context).apply { setBackgroundColor(0x0AFFFFFF) },
            LayoutParams(context.dp(1), LayoutParams.MATCH_PARENT, Gravity.END))
        val barTop = IndexTokens.RAIL_HOME_Y + (IndexTokens.RAIL_ITEM_H - IndexTokens.RAIL_BAR_H) / 2
        addView(bar, LayoutParams(context.dp(IndexTokens.RAIL_BAR_W), context.dp(IndexTokens.RAIL_BAR_H)).apply { topMargin = context.dp(barTop) })
        items().forEach { addView(it) }
        paintBar()
    }

    /** The accent can change in Customize after this view was built. */
    fun paintBar() {
        if (barColor == Tokens.accent) return
        barColor = Tokens.accent
        (bar.background as GradientDrawable).setColor(barColor)
        // The items' focus glow and the current icon bake the accent in too.
        items().forEach {
            it.background = focusBackground()
            it.paint()
        }
    }

    /** Search has the list's place: the bar glides up to it, and it becomes the current item. */
    fun setSearch(open: Boolean) {
        if (open == searching) return
        searching = open
        searchItem.current = open
        homeItem.current = !open
        val up = -context.dpf((IndexTokens.RAIL_HOME_Y - IndexTokens.RAIL_SEARCH_Y).toFloat())
        bar.animate().cancel()
        bar.animate().translationY(if (open) up else 0f).setDuration(Tokens.GLIDE_MS).setInterpolator(Tokens.ease).start()
    }

    /** The item for the page showing: where Left from the list or the panel lands. */
    fun current(): View = if (searching) searchItem else homeItem

    /** Hidden when the TV has no inputs to switch to. */
    fun setHasInputs(has: Boolean, animate: Boolean) {
        inputsItem.appear(has, animate)
    }

    /** The next item up or down from [from], or null at the end. */
    fun neighbour(from: View, down: Boolean): View? = Order.step(visible(), from, down)

    private fun items() = listOf(searchItem, homeItem, inputsItem, settingsItem)

    /** The items the remote reaches, top to bottom. */
    private fun visible() = items().filter { it.visibility == VISIBLE }

    private fun focusBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), Focus.tileRing(context, 8f, 0x1AFFFFFF))
        addState(intArrayOf(), android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
    }

    /** The neighbour of [from] in [items], or null past either end; pure so the rail's walk is tested. */
    object Order {
        fun <T> step(items: List<T>, from: T, down: Boolean): T? {
            val i = items.indexOf(from)
            if (i < 0) return null
            return items.getOrNull(if (down) i + 1 else i - 1)
        }
    }

    private inner class Item(icon: Int, label: Int, onClick: () -> Unit) : LinearLayout(context) {
        private val glyph = ImageView(context).apply { setImageResource(icon) }
        private val text = IndexText(context, Type.CAPTION, 500, IndexTokens.railIdle).apply {
            maxLines = 1
            this.text = context.getString(label)
        }

        /** The page showing: accent icon, white label, and the bar beside it. */
        var current = false
            set(value) {
                field = value
                paint()
            }

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            contentDescription = context.getString(label)
            setOnClickListener { onClick() }
            background = focusBackground()
            isFocusable = true
            isClickable = true
            addView(glyph, LayoutParams(context.dp(IndexTokens.RAIL_ICON), context.dp(IndexTokens.RAIL_ICON)))
            addView(text, text.stack(LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT), top = context.dp(4)))
            paint()
        }

        /** Colours only: the current icon is the accent, a focused or current label is white. */
        fun paint() {
            val lit = current || isFocused
            glyph.setColorFilter(if (current) Tokens.accent else if (lit) IndexTokens.railActive else IndexTokens.railIdle)
            text.setTextColor(if (lit) IndexTokens.railActive else IndexTokens.railIdle)
        }

        override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
            val scale = if (gainFocus) Tokens.FOCUS_SCALE else 1f
            animate().scaleX(scale).scaleY(scale)
                .setDuration(if (gainFocus) Tokens.FOCUS_IN_MS else Tokens.FOCUS_OUT_MS).setInterpolator(Tokens.ease).start()
            paint()
        }
    }

    private fun item(icon: Int, label: Int, y: Int, onClick: () -> Unit): Item = Item(icon, label, onClick).apply {
        layoutParams = FrameLayout.LayoutParams(context.dp(IndexTokens.RAIL_ITEM_W), context.dp(IndexTokens.RAIL_ITEM_H)).apply {
            leftMargin = context.dp((IndexTokens.RAIL_W - IndexTokens.RAIL_ITEM_W) / 2)
            topMargin = context.dp(y)
        }
    }
}
