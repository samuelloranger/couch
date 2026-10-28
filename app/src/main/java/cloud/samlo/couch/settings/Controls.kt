package cloud.samlo.couch.settings

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf

/**
 * The controls a row shows in place of its plain value, so each kind of row looks like what it
 * does: a switch, every choice side by side, or the colours themselves. They only draw; the row
 * keeps focus and handles the keys.
 */

/** A choice short enough to show whole: every option side by side, the current one lit. */
@SuppressLint("ViewConstructor")
class Segments(context: Context, labels: List<String>) : LinearLayout(context) {
    private val cells = labels.map { label ->
        TextView(context).apply {
            text = label
            textSize = 14f
            maxLines = 1
            setPadding(context.dp(12), context.dp(4), context.dp(12), context.dp(4))
        }
    }

    init {
        orientation = HORIZONTAL
        val pad = context.dp(3)
        setPadding(pad, pad, pad, pad)
        background = rounded(context, 9f, Tokens.surface)
        cells.forEach { addView(it) }
    }

    fun select(index: Int) = cells.forEachIndexed { i, cell ->
        val on = i == index
        cell.background = if (on) rounded(context, 7f, Tokens.textPrimary) else null
        cell.setTextColor(if (on) Tokens.background else Tokens.textMuted)
        cell.typeface = Typeface.create(if (on) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }

    companion object {
        /** Past this many characters in all, the options no longer fit beside the row's title. */
        private const val MAX_CHARS = 24

        fun fit(labels: List<String>) = labels.size in 2..3 && labels.sumOf { it.length } <= MAX_CHARS
    }
}

/** The accent colours as dots; the current one gets a ring. */
@SuppressLint("ViewConstructor")
class Swatches(context: Context, private val colors: List<Int>) : LinearLayout(context) {
    private val dots = colors.map { View(context) }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        dots.forEachIndexed { i, dot ->
            addView(dot, LayoutParams(context.dp(SIZE), context.dp(SIZE)).apply { if (i > 0) marginStart = context.dp(6) })
        }
    }

    fun select(index: Int) = dots.forEachIndexed { i, dot -> dot.background = dot(colors[i], i == index) }

    private fun dot(color: Int, on: Boolean): Drawable {
        val fill = oval(color)
        val inset = context.dp(4)
        if (!on) return LayerDrawable(arrayOf(fill)).apply { setLayerInset(0, inset, inset, inset, inset) }
        // A light ring, a dark gap, then the colour: readable around White too.
        return LayerDrawable(arrayOf(oval(Tokens.textPrimary), oval(Tokens.background), fill)).apply {
            val gap = context.dp(2)
            setLayerInset(1, gap, gap, gap, gap)
            setLayerInset(2, gap * 2, gap * 2, gap * 2, gap * 2)
        }
    }

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private companion object {
        const val SIZE = 24
    }
}

/** On or off, drawn as a switch in the accent colour. */
class SwitchView(context: Context) : View(context) {
    var on = false
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(context.dp(40), context.dp(22))

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        track.set(0f, 0f, width.toFloat(), h)
        // The accent is read at each draw, so a new accent shows without rebuilding the row.
        paint.color = if (on) Tokens.accent else OFF_TRACK
        canvas.drawRoundRect(track, h / 2, h / 2, paint)
        val r = context.dpf(8f)
        val inset = (h / 2) - r
        paint.color = if (on) Tokens.background else Tokens.textMuted
        canvas.drawCircle(if (on) width - inset - r else inset + r, h / 2, r, paint)
    }

    private companion object {
        val OFF_TRACK = Color.parseColor("#2E3633")
    }
}

/** A thin line under a value that opens the keyboard, the way a text field looks. */
fun underline(context: Context): Drawable {
    val width = context.dp(1)
    val line = GradientDrawable().apply { setStroke(width + 1, (Tokens.textMuted and 0x00FFFFFF) or 0x99000000.toInt()) }
    // Pushed past the other three edges, so only the bottom stroke shows.
    return LayerDrawable(arrayOf(line)).apply { setLayerInset(0, -width * 3, -width * 3, -width * 3, 0) }
}

private fun rounded(context: Context, radius: Float, color: Int) = GradientDrawable().apply {
    cornerRadius = context.dpf(radius)
    setColor(color)
}
