package cloud.samlo.couch.index

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import cloud.samlo.couch.ui.FlatView
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.arrange
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf

/**
 * The app list on the left. Like Couch's rows, it never scrolls: the track glides by
 * translation so the selected row keeps [IndexTokens.ROWS_ABOVE] rows above it, and the last
 * rows stop above the fade at the bottom. The selected row's background and accent marker are
 * one view behind the rows that glides from row to row, also by translation.
 */
@SuppressLint("ViewConstructor")
class AppList(context: Context, private val makeRow: (IndexEntry) -> AppRow) : FrameLayout(context) {
    /** The rows and the selection behind them, moving as one. */
    private val track = FrameLayout(context)
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val fill = GradientDrawable().apply { cornerRadius = context.dpf(6f) }
    private val marker = GradientDrawable().apply { cornerRadius = context.dpf(1.5f) }
    private val selection = FrameLayout(context).apply {
        background = fill
        visibility = INVISIBLE
        addView(FlatView(context).apply { background = marker }, LayoutParams(context.dp(3), context.dp(IndexTokens.ROW_H - 22), Gravity.CENTER_VERTICAL))
    }
    private var selectionY = 0f
    /** In-row state and accent as last painted; repainting only on a change keeps a key press from redrawing it. */
    private var painted: Pair<Boolean, Int>? = null

    init {
        clipChildren = true
        track.addView(selection, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(IndexTokens.ROW_H)))
        track.addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(track, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val fadeTop = context.dp(IndexTokens.LIST_FADE_Y)
        addView(FlatView(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Tokens.background and 0x00FFFFFF, Tokens.background),
            )
        }, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(IndexTokens.LIST_H) - fadeTop).apply { topMargin = fadeTop })
    }

    /** The track keeps its full height, like a scroll view's content; this view clips it. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        track.measure(MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
    }

    fun rows(): List<AppRow> = (0 until column.childCount).map { column.getChildAt(it) as AppRow }

    /** Shows [entries] in order, reusing rows by app; only rows whose data changed are rebound. */
    fun bind(entries: List<IndexEntry>, rebuildAll: Boolean, moveFocus: (AppRow) -> Unit) {
        val existing = rows().associateBy { it.entry.key }
        val wanted = entries.map { e ->
            val kept = existing[e.key]?.takeIf { !rebuildAll }
            kept?.also { if (it.entry.signature != e.signature) it.bind(e) } ?: makeRow(e).apply {
                layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(IndexTokens.ROW_H)).apply {
                    bottomMargin = context.dp(IndexTokens.ROW_PITCH - IndexTokens.ROW_H)
                }
            }
        }
        if (wanted.isEmpty()) {
            column.removeAllViews()
            selection.visibility = INVISIBLE
            return
        }
        // The focused app went away (hidden, uninstalled): focus the one now in its place.
        column.arrange(wanted) { _, index -> moveFocus(wanted[index.coerceAtMost(wanted.lastIndex)]) }
    }

    /** Makes [row] the selected one; [inRow] while focus is in its channel row, which quietens it. */
    fun select(row: AppRow, inRow: Boolean) {
        rows().forEach { it.chosen = it === row }
        if (painted != inRow to Tokens.accent) {
            painted = inRow to Tokens.accent
            fill.setColor(if (inRow) Tokens.surface else Tokens.surfaceFocused)
            marker.setColor(if (inRow) IndexTokens.markerIdle else Tokens.accent)
        }
        val target = (column.indexOfChild(row) * context.dp(IndexTokens.ROW_PITCH)).toFloat()
        if (selection.visibility != VISIBLE) {
            selectionY = target
            selection.translationY = target
            selection.visibility = VISIBLE
            return
        }
        if (target == selectionY) return
        selectionY = target
        selection.animate().translationY(target).setDuration(Tokens.GLIDE_MS).setInterpolator(Tokens.ease).start()
    }

    /** Glides so row [index] sits where the list wants it. Without [animate], the selection lands at once too. */
    fun reveal(index: Int, animate: Boolean = true) {
        val count = column.childCount
        val first = (index - IndexTokens.ROWS_ABOVE).coerceAtMost(count - IndexTokens.ROWS_SHOWN).coerceAtLeast(0)
        val target = -(first * context.dp(IndexTokens.ROW_PITCH)).toFloat()
        track.animate().cancel()
        if (!animate) {
            track.translationY = target
            selection.animate().cancel()
            selection.translationY = selectionY
            return
        }
        if (track.translationY == target) return
        track.animate().translationY(target).setDuration(Tokens.GLIDE_MS).setInterpolator(Tokens.ease).start()
    }
}
