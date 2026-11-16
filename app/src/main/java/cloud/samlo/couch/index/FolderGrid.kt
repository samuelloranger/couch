package cloud.samlo.couch.index

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import cloud.samlo.couch.data.Tile
import cloud.samlo.couch.ui.Card
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp

/**
 * A folder's apps, where the channel row sits: a 3-column grid of app cards, two rows in view.
 * Like the app list it never scrolls: a folder with more apps glides the grid by translation when
 * focus reaches a row below the second, so a move costs no layout pass.
 */
@SuppressLint("ViewConstructor")
class FolderGrid(context: Context, private val makeCard: (Tile) -> Card) : FrameLayout(context) {
    /** The rows of cards, which glide under this view's clip. */
    private val track = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var cards: List<Card> = emptyList()
    /** The folder shown, and the look its cards were drawn with. */
    private var folder: String? = null
    private var look = ""

    init {
        clipChildren = true
        addView(track, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** The rows keep their full height inside the grid's box, like a scroll view's content: the box clips them. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        track.measure(
            MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
    }

    /**
     * Shows [entries], the apps of the folder keyed [key], as [look] draws them. The same folder
     * with the same apps is left alone, so a reload doesn't rebuild its cards.
     */
    fun show(key: String, entries: List<IndexEntry>, look: String) {
        val unchanged = key == folder && look == this.look && cards.size == entries.size &&
            cards.indices.all { cards[it].tile.signature == entries[it].app.signature }
        if (unchanged) return
        // Rebuilding detaches every card, so the tile under the remote is put back afterwards.
        val hadFocus = cards.firstOrNull { it.hasFocus() }?.tile?.key
        folder = key
        this.look = look
        track.removeAllViews()
        track.animate().cancel()
        track.translationY = 0f
        cards = entries.map { makeCard(it.app) }
        cards.chunked(COLUMNS).forEach { row ->
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEachIndexed { i, card ->
                line.addView(card)
                // A card carries the focus ring's room on every side: the gaps give it back.
                val params = card.layoutParams as LinearLayout.LayoutParams
                params.marginStart = if (i == 0) 0 else context.dp(COL_GAP - 2 * Tokens.RING_INSET)
                card.layoutParams = params
            }
            track.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (track.childCount > 0) topMargin = context.dp(ROW_GAP - 2 * Tokens.RING_INSET)
            })
        }
        if (hadFocus != null) (cards.firstOrNull { it.tile.key == hadFocus } ?: cards.firstOrNull())?.requestFocus()
    }

    /** The first tile, for Right from the folder row; null while the folder has none. */
    fun first(): View? = cards.firstOrNull()

    /**
     * The tile [direction] leads to among the cards, or null when it leaves the grid: left from the
     * first column and up from the top row go back to the folder row. At the last column or row it
     * returns [focused], so focus stays in the grid.
     */
    fun next(focused: View, direction: Int): View? {
        val i = cards.indexOfFirst { it === focused }
        if (i < 0) return null
        val column = i % COLUMNS
        return when (direction) {
            FOCUS_LEFT -> if (column == 0) null else cards[i - 1]
            FOCUS_UP -> if (i < COLUMNS) null else cards[i - COLUMNS]
            FOCUS_RIGHT -> if (column == COLUMNS - 1) focused else cards.getOrNull(i + 1) ?: focused
            FOCUS_DOWN -> cards.getOrNull(i + COLUMNS) ?: focused
            else -> focused
        }
    }

    /** Glides the rows so the focused card's row shows, as the second row when it is below that. */
    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        val at = cards.indexOfFirst { it === focused }
        if (at < 0) return
        val first = ((at / COLUMNS) - (ROWS - 1)).coerceIn(0, (track.childCount - ROWS).coerceAtLeast(0))
        val target = -(first * context.dp(Tokens.APP_H + ROW_GAP)).toFloat()
        if (track.translationY == target) return
        track.animate().translationY(target).setDuration(Tokens.GLIDE_MS).setInterpolator(Tokens.ease).start()
    }

    companion object {
        const val COLUMNS = 3
        const val ROWS = 2
        const val COL_GAP = 20
        const val ROW_GAP = 14

        /** The grid on the Index canvas, the ring's room included. */
        const val W = Tokens.APP_W * COLUMNS + COL_GAP * (COLUMNS - 1) + 2 * Tokens.RING_INSET
        const val H = Tokens.APP_H * ROWS + ROW_GAP * (ROWS - 1) + 2 * Tokens.RING_INSET
    }
}
