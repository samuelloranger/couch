package cloud.samlo.couch.index

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.LinearLayout
import cloud.samlo.couch.ui.FlatView
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.arrange
import cloud.samlo.couch.ui.dp

/**
 * The app list on the left. Like Couch's rows, it never scrolls: the column glides by
 * translation so the selected row keeps [IndexTokens.ROWS_ABOVE] rows above it, and the last
 * rows stop above the fade at the bottom.
 */
@SuppressLint("ViewConstructor")
class AppList(context: Context, private val makeRow: (IndexEntry) -> AppRow) : FrameLayout(context) {
    val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    init {
        clipChildren = true
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val fadeTop = context.dp(IndexTokens.LIST_FADE_Y)
        addView(FlatView(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Tokens.background and 0x00FFFFFF, Tokens.background),
            )
        }, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(IndexTokens.LIST_H) - fadeTop).apply { topMargin = fadeTop })
    }

    /** The column keeps its full height, like a scroll view's content; this view clips it. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        column.measure(MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
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
            return
        }
        // The focused app went away (hidden, uninstalled): focus the one now in its place.
        column.arrange(wanted) { _, index -> moveFocus(wanted[index.coerceAtMost(wanted.lastIndex)]) }
    }

    /** Glides so row [index] sits where the list wants it. */
    fun reveal(index: Int, animate: Boolean = true) {
        val count = column.childCount
        val first = (index - IndexTokens.ROWS_ABOVE).coerceAtMost(count - IndexTokens.ROWS_SHOWN).coerceAtLeast(0)
        val target = -(first * context.dp(IndexTokens.ROW_PITCH)).toFloat()
        column.animate().cancel()
        if (!animate) {
            column.translationY = target
            return
        }
        if (column.translationY == target) return
        column.animate().translationY(target).setDuration(Tokens.GLIDE_MS).setInterpolator(Tokens.ease).start()
    }
}
