package cloud.samlo.couch.index

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import cloud.samlo.couch.ui.FlatView
import cloud.samlo.couch.ui.Focus
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.arrange
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf
import cloud.samlo.couch.ui.reveal

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
    private val marker = GradientDrawable().apply { cornerRadius = context.dpf(1f) }
    private val selection = FrameLayout(context).apply {
        background = fill
        visibility = INVISIBLE
        addView(FlatView(context).apply { background = marker }, LayoutParams(context.dp(IndexTokens.LIST_MARKER_W), context.dp(IndexTokens.LIST_MARKER_H), Gravity.CENTER_VERTICAL))
    }
    private var selectionY = 0f
    private val fade = FlatView(context).apply {
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Tokens.background and 0x00FFFFFF, Tokens.background),
        )
    }
    private var rowsShown = IndexTokens.ROWS_SHOWN
    /** In-row state and accent as last painted; repainting only on a change keeps a key press from redrawing it. */
    private var painted: Pair<Boolean, Int>? = null
    /** The row lifted for a manual move, and where it sits in the column. */
    private var lifted: AppRow? = null
    private var liftedIndex = -1
    /** The row wearing the selection, which a lift's frame follows. */
    private var chosen: AppRow? = null

    init {
        clipChildren = true
        track.addView(selection, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(IndexTokens.LIST_ROW_H)))
        track.addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(track, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(fade, LayoutParams(LayoutParams.MATCH_PARENT, 0))
        fit(IndexTokens.LIST_H, IndexTokens.LIST_FADE_Y, IndexTokens.ROWS_SHOWN)
    }

    /** The list's height in dp, where its fade starts and how many rows show before it. */
    fun fit(height: Int, fadeY: Int, rows: Int) {
        rowsShown = rows
        fade.layoutParams = (fade.layoutParams as LayoutParams).apply {
            topMargin = context.dp(fadeY)
            this.height = context.dp(height) - context.dp(fadeY)
        }
    }

    /** The track keeps its full height, like a scroll view's content; this view clips it. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        track.measure(MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
    }

    fun rows(): List<AppRow> = (0 until column.childCount).map { column.getChildAt(it) as AppRow }

    /** Shows [entries] in order, reusing rows by app; only rows whose data changed are rebound. With [animate], newly added apps fade in. */
    fun bind(entries: List<IndexEntry>, rebuildAll: Boolean, animate: Boolean, moveFocus: (AppRow) -> Unit) {
        val existing = rows().associateBy { it.entry.key }
        val wanted = entries.map { e ->
            val kept = existing[e.key]?.takeIf { !rebuildAll }
            kept?.also { if (it.entry.signature != e.signature) it.bind(e) } ?: makeRow(e).apply {
                layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(IndexTokens.LIST_ROW_H)).apply {
                    bottomMargin = context.dp(IndexTokens.LIST_ROW_PITCH - IndexTokens.LIST_ROW_H)
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
        // A rebuild (accent change) replaces every row; only apps not listed before are new.
        if (animate && !rebuildAll) wanted.filter { it.entry.key !in existing }.forEach { it.reveal() }
    }

    /** Makes [row] the selected one; [inRow] while focus is in its channel row, which quietens it. */
    fun select(row: AppRow, inRow: Boolean) {
        rows().forEach { it.chosen = it === row }
        chosen = row
        if (painted != inRow to Tokens.accent) {
            painted = inRow to Tokens.accent
            fill.setColor(if (inRow) SELECTED_QUIET else SELECTED)
            marker.setColor(if (inRow) Tokens.graphicIdle else Tokens.accent)
        }
        place(column.indexOfChild(row), animate = true)
    }

    /**
     * Lifts [row] out of the list for a manual move: an accent outline, a shadow, and a step to the
     * right. The row keeps its place in the column; [shift] shows it elsewhere without moving it.
     */
    fun lift(row: AppRow) {
        lifted = row
        liftedIndex = column.indexOfChild(row)
        row.lifted = true
        row.elevation = context.dpf(LIFT_Z)
        // The step right would leave the list, which clips its rows: the row gives up as much width.
        (row.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.marginEnd = context.dp(LIFT_X)
            row.requestLayout()
        }
        row.animate().translationX(context.dp(LIFT_X).toFloat()).setDuration(Tokens.GLIDE_MS).setInterpolator(Tokens.ease).start()
    }

    /**
     * Shows the lifted row [offset] places from where it started: it and the rows it passes glide
     * one pitch, so the list reads in its new order before anything is saved.
     */
    fun shift(offset: Int, animate: Boolean = true) {
        val row = lifted ?: return
        val pitch = context.dp(IndexTokens.LIST_ROW_PITCH).toFloat()
        rows().forEachIndexed { i, other ->
            val target = when {
                other === row -> offset * pitch
                offset > 0 && i in (liftedIndex + 1)..(liftedIndex + offset) -> -pitch
                offset < 0 && i in (liftedIndex + offset) until liftedIndex -> pitch
                else -> 0f
            }
            other.animate().cancel()
            if (animate) other.animate().translationY(target).setDuration(Tokens.GLIDE_MS).setInterpolator(Tokens.ease).start()
            else other.translationY = target
        }
        place(liftedIndex + offset, animate)
    }

    /** Puts the lifted row back in line: the list shows its saved order again. */
    fun endLift() {
        val row = lifted ?: return
        row.lifted = false
        row.elevation = 0f
        (row.layoutParams as? LinearLayout.LayoutParams)?.let {
            it.marginEnd = 0
            row.requestLayout()
        }
        row.animate().cancel()
        row.translationX = 0f
        rows().forEach {
            it.animate().cancel()
            it.translationY = 0f
        }
        lifted = null
        liftedIndex = -1
        // The selection frame goes back to the chosen row, which never moved.
        chosen?.let { place(column.indexOfChild(it), animate = false) }
    }

    /** Glides the selection frame to row [index], or drops it there when [animate] is false. */
    private fun place(index: Int, animate: Boolean) {
        if (index < 0) return
        val target = (index * context.dp(IndexTokens.LIST_ROW_PITCH)).toFloat()
        if (selection.visibility != VISIBLE) {
            selectionY = target
            selection.translationY = target
            selection.visibility = VISIBLE
            return
        }
        if (target == selectionY) return
        selectionY = target
        selection.animate().cancel()
        if (!animate) {
            selection.translationY = target
            return
        }
        selection.animate().translationY(target).setDuration(Tokens.GLIDE_MS).setInterpolator(Tokens.ease).start()
    }

    /** Glides so row [index] sits where the list wants it. Without [animate], the selection lands at once too. */
    fun reveal(index: Int, animate: Boolean = true) {
        val count = column.childCount
        val first = (index - IndexTokens.ROWS_ABOVE).coerceAtMost(count - rowsShown).coerceAtLeast(0)
        val target = -(first * context.dp(IndexTokens.LIST_ROW_PITCH)).toFloat()
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

    private companion object {
        /** White at 6%, and a little less while focus is in the channel row. */
        const val SELECTED = Focus.PLATE
        const val SELECTED_QUIET = Focus.PLATE_QUIET
        /** How far a lifted row steps to the right, and how high it floats. */
        const val LIFT_X = 6
        const val LIFT_Z = 8f
    }
}
