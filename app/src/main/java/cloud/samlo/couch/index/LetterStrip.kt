package cloud.samlo.couch.index

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import cloud.samlo.couch.ui.Focus
import cloud.samlo.couch.ui.Fonts
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.Type
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf
import android.view.View

/**
 * The A to Z strip under Search's field: one focusable view that paints all of [Letters.ALL] in
 * two rows, so a move repaints and never lays out. Letters with no app are dimmed and skipped;
 * the chosen one wears a plate, and a ring while the strip has focus. [SearchPanel] feeds it keys.
 */
@SuppressLint("ViewConstructor")
class LetterStrip(context: Context) : View(context) {
    /** The letters that have apps. */
    var present: Set<Char> = emptySet()
        set(value) {
            field = value
            invalidate()
        }

    /** The chosen letter, or null while no letter filters the results. */
    var letter: Char? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Fonts.hanken(context, 600)
        textSize = context.dpf(Type.LABEL)
        textAlign = Paint.Align.CENTER
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(Focus.RING_W.toFloat())
    }
    private val cell = RectF()

    init {
        isFocusable = true
        contentDescription = context.getString(cloud.samlo.couch.R.string.search_letters)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(context.dp(PER_ROW * CELL_W), context.dp(HEIGHT))

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = context.dpf(CELL_W.toFloat())
        val h = context.dpf(CELL_H.toFloat())
        val radius = context.dpf(Focus.ROW_RADIUS)
        val centre = (text.descent() + text.ascent()) / 2
        Letters.ALL.forEachIndexed { i, c ->
            val x = (i % PER_ROW) * w
            val y = (i / PER_ROW) * context.dpf((CELL_H + ROW_GAP).toFloat())
            cell.set(x, y, x + w, y + h)
            val chosen = c == letter
            if (chosen) {
                fill.color = if (hasFocus()) Focus.PLATE else Focus.PLATE_QUIET
                canvas.drawRoundRect(cell, radius, radius, fill)
                if (hasFocus()) {
                    stroke.color = Tokens.ring
                    cell.inset(stroke.strokeWidth / 2, stroke.strokeWidth / 2)
                    canvas.drawRoundRect(cell, radius, radius, stroke)
                }
            }
            text.color = when {
                chosen -> Tokens.textPrimary
                c in present -> IndexTokens.pillText
                else -> ABSENT
            }
            canvas.drawText(c.toString(), x + w / 2, y + h / 2 - centre, text)
        }
    }

    companion object {
        const val CELL_W = 20
        const val CELL_H = 28
        const val ROW_GAP = 2
        const val PER_ROW = 14
        const val HEIGHT = CELL_H * 2 + ROW_GAP
        /** A letter with no app: still drawn so the strip keeps its shape, but plainly out of play. */
        val ABSENT = android.graphics.Color.parseColor("#4A5551")
    }
}
