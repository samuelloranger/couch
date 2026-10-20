package cloud.samlo.couch.ui

import android.content.Context
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import cloud.samlo.couch.data.Tile

/**
 * A horizontal row that keeps the focused card at a fixed spot on the left edge and glides the
 * content under it, and that hands focus back to the card you left when you come back from
 * another row.
 *
 * It never actually scrolls: the strip is translated with a ViewPropertyAnimator. Each move
 * retargets the running animation from wherever it is, so holding the D-pad glides instead of
 * jump-cutting (the stock smooth scroll snaps when a new scroll lands within 250ms of the last),
 * and translation is a render property, so moving costs no redraw.
 */
class FocusRow(context: Context) : HorizontalScrollView(context) {
    private var lastKey: String? = null
    private var lastIndex = 0

    /** The visible gap between cards, in dp; call [fixMargins] after changing it. */
    var gapDp = Tokens.CARD_GAP

    val strip: LinearLayout = object : LinearLayout(context) {
        init {
            isChildrenDrawingOrderEnabled = true
        }

        /** The focused card draws last, so its scaled-up ring covers its neighbours. */
        override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int {
            val focused = indexOfChild(focusedChild)
            if (focused < 0) return drawingPosition
            return when {
                drawingPosition == childCount - 1 -> focused
                drawingPosition >= focused -> drawingPosition + 1
                else -> drawingPosition
            }
        }

        override fun requestChildFocus(child: View?, focused: View?) {
            super.requestChildFocus(child, focused)
            (child as? Card)?.let {
                lastKey = it.tile.key
                lastIndex = indexOfChild(it)
                reveal(it)
            }
        }
    }

    init {
        isHorizontalScrollBarEnabled = false
        isHorizontalFadingEdgeEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFocusable = false
        descendantFocusability = FOCUS_AFTER_DESCENDANTS
        val padV = context.dp(10)
        strip.setPadding(context.dp(Tokens.OVERSCAN_H) - context.dp(Tokens.RING_INSET), padV, context.dp(Tokens.OVERSCAN_H), padV)
        strip.clipChildren = false
        strip.clipToPadding = false
        addView(strip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    /**
     * Shows [tiles], reusing every card whose tile hasn't changed. The focused card is never
     * detached before focus has a new home, so Android never throws focus somewhere arbitrary.
     */
    fun update(tiles: List<Tile>, look: String, make: (Tile) -> Card) {
        val existing = cards().associateBy { it.tile.key }
        // A card is reused only if both its data and the look it was drawn with are unchanged.
        val wanted = tiles.map { t -> existing[t.key]?.takeIf { it.tile.signature == t.signature && it.look == look } ?: make(t) }
        strip.arrange(wanted) { old, index ->
            val key = (old as Card).tile.key
            (wanted.firstOrNull { it.tile.key == key } ?: wanted.getOrNull(index.coerceAtMost(wanted.lastIndex)))
                ?.requestFocus()
        }
        fixMargins()
        // Widths changed: re-clamp once the new layout is in.
        post { remembered()?.let { reveal(it) } }
    }

    /** Space before the first card, where it rests when focused; the row clips anything left of its own edge. */
    fun setStartPadding(px: Int) {
        strip.setPadding(px, strip.paddingTop, strip.paddingRight, strip.paddingBottom)
    }

    /** Gaps go between cards only; call after reordering. */
    fun fixMargins() {
        val gap = context.dp(gapDp) - context.dp(Tokens.RING_INSET) * 2
        for (i in 0 until strip.childCount) {
            val params = strip.getChildAt(i).layoutParams as LinearLayout.LayoutParams
            val margin = if (i == 0) 0 else gap
            if (params.marginStart != margin) {
                params.marginStart = margin
                strip.getChildAt(i).layoutParams = params
            }
        }
    }

    fun cards(): List<Card> = (0 until strip.childCount).map { strip.getChildAt(it) as Card }

    /** Starts over at the first card, for a row about to be refilled with something else. */
    fun forget() {
        lastKey = null
        lastIndex = 0
        strip.animate().cancel()
        strip.translationX = 0f
    }

    fun focusFirst() {
        strip.animate().cancel()
        strip.translationX = 0f
        strip.getChildAt(0)?.requestFocus()
    }

    fun restoreFocus(): Boolean = remembered()?.requestFocus() == true

    /** The card focused last time, or the one now at its position if it's gone. */
    private fun remembered(): View? {
        if (strip.childCount == 0) return null
        return cards().firstOrNull { it.tile.key == lastKey } ?: strip.getChildAt(lastIndex.coerceIn(0, strip.childCount - 1))
    }

    /** Glides so [card] sits at the row's start, or as far as the row allows. */
    fun reveal(card: View) {
        val maxShift = (strip.width - width).coerceAtLeast(0)
        val target = -(card.left - strip.paddingStart).coerceIn(0, maxShift).toFloat()
        if (strip.translationX == target) return
        strip.animate().translationX(target).setDuration(Tokens.GLIDE_MS).setInterpolator(Tokens.ease).start()
    }

    /** Left/right never leave the row: at either end, focus stays put. */
    override fun focusSearch(focused: View?, direction: Int): View? {
        val next = super.focusSearch(focused, direction)
        if ((direction == FOCUS_LEFT || direction == FOCUS_RIGHT) && next != null && !contains(next)) return focused
        return next
    }

    /** Coming from another row, only the card last focused here is a candidate. */
    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        val last = remembered()
        if (!hasFocus() && last != null && (direction == FOCUS_UP || direction == FOCUS_DOWN)) {
            views.add(last)
            return
        }
        super.addFocusables(views, direction, focusableMode)
    }

    // The stock scrolling is replaced by reveal(): no key-driven paging, no scroll-to-child.
    override fun executeKeyEvent(event: KeyEvent) = false
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect) = 0

    private fun contains(view: View): Boolean {
        var parent = view.parent
        while (parent != null) {
            if (parent === this) return true
            parent = parent.parent
        }
        return false
    }
}
