package cloud.samlo.couch.remote

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import cloud.samlo.couch.index.IndexText
import cloud.samlo.couch.index.IndexTokens
import cloud.samlo.couch.ui.FlatView
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf

/**
 * What a panel draws over the playing picture: a dark fade from the left edge (the right one when
 * [end]), and on it the
 * rows, each section under its title, and a [hint]. As in the Index list, the selection is one
 * view behind the rows, and the list glides by translation to keep it in sight, so moving never
 * lays out.
 */
@SuppressLint("ViewConstructor")
class PanelView(context: Context, private val rows: List<PanelRow>, hint: String, end: Boolean) : FrameLayout(context) {
    private val selection = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            cornerRadius = context.dpf(6f)
            setColor(Tokens.surfaceFocused)
        }
        addView(
            FlatView(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = context.dpf(1.5f)
                    setColor(Tokens.accent)
                }
            },
            LayoutParams(context.dp(3), context.dp(IndexTokens.ROW_H - 22), Gravity.CENTER_VERTICAL),
        )
        visibility = INVISIBLE
    }
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    /** The titles, rows and selection, moving as one. */
    private val track = FrameLayout(context)
    /** Shows as much of [track] as fits in the screen's safe area. */
    private val viewport = object : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            track.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val most = resources.displayMetrics.heightPixels - 2 * context.dp(Tokens.OVERSCAN_V) - context.dp(HINT_H)
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), track.measuredHeight.coerceAtMost(most))
        }
    }
    val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var selected = -1
    /** Gets every key while the panel has focus; false lets it go on. */
    var onKey: ((KeyEvent) -> Boolean)? = null

    init {
        isFocusable = true
        val bg = Tokens.background and 0x00FFFFFF
        // A light dim over the whole picture, then a near-opaque fade behind the list so what
        // plays underneath never reads through the text.
        addView(FlatView(context).apply { setBackgroundColor(bg or 0x66000000) }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            FlatView(context).apply {
                background = GradientDrawable(
                    if (end) GradientDrawable.Orientation.RIGHT_LEFT else GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(bg or 0xFA000000.toInt(), bg or 0xF5000000.toInt(), bg or 0xCC000000.toInt(), bg),
                )
            },
            LayoutParams(context.dp(SCRIM_W), LayoutParams.MATCH_PARENT, if (end) Gravity.END else Gravity.START),
        )
        var section: String? = null
        rows.forEach { row ->
            if (row.item.section != section) {
                section = row.item.section
                title(if (column.childCount == 0) 0 else context.dp(SECTION_GAP), row.item.section)
            }
            column.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(IndexTokens.ROW_H)).apply {
                bottomMargin = context.dp(IndexTokens.ROW_PITCH - IndexTokens.ROW_H)
            })
        }
        track.addView(selection, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(IndexTokens.ROW_H)))
        track.addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        viewport.addView(track, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        content.addView(viewport, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val hintView = IndexText(context, 11f, 500, IndexTokens.hours).apply { text = hint }
        content.addView(hintView, hintView.stack(LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dp(15)
        }, top = context.dp(10)))
        addView(content, LayoutParams(context.dp(PANEL_W), LayoutParams.WRAP_CONTENT, (if (end) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL).apply {
            if (end) rightMargin = context.dp(Tokens.OVERSCAN_H) else leftMargin = context.dp(Tokens.OVERSCAN_H)
        })
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = onKey?.invoke(event) == true || super.dispatchKeyEvent(event)

    private fun title(top: Int, text: String) {
        val view = IndexText(context, 15f, 600, Tokens.textMuted).apply { this.text = text }
        column.addView(view, view.stack(LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = context.dp(15)
        }, top = top, bottom = context.dp(12)))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // The first selection waits for the rows to have places.
        if (selected >= 0 && selection.visibility != VISIBLE) place(animate = false)
    }

    fun select(index: Int) {
        if (index == selected || index !in rows.indices) return
        selected = index
        rows.forEachIndexed { i, row -> row.chosen = i == index }
        if (isLaidOut) place(animate = selection.visibility == VISIBLE)
    }

    /** Moves the selection to the selected row, scrolling so a couple of rows stay visible above it. */
    private fun place(animate: Boolean) {
        val y = rows[selected].top.toFloat()
        val room = (track.height - viewport.height).coerceAtLeast(0).toFloat()
        val scroll = -(y - context.dp(IndexTokens.ROW_PITCH * ROWS_ABOVE)).coerceIn(0f, room)
        if (!animate) {
            selection.translationY = y
            track.translationY = scroll
            selection.visibility = VISIBLE
            return
        }
        selection.animate().translationY(y).setDuration(Tokens.GLIDE_MS).setInterpolator(Tokens.ease).start()
        track.animate().translationY(scroll).setDuration(Tokens.GLIDE_MS).setInterpolator(Tokens.ease).start()
    }

    companion object {
        const val PANEL_W = 340
        const val SCRIM_W = 760
        const val SECTION_GAP = 18
        /** The hint under the list, with its gap. */
        const val HINT_H = 30
        const val ROWS_ABOVE = 2
    }
}
