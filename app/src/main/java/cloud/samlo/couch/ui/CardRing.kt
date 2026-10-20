package cloud.samlo.couch.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View

/**
 * The focus ring: a soft glow outside a crisp stroke. Layer 1 is the stroke. [radiusDp] is the
 * card's own corner radius; [thin] draws a finer ring with a small gap around the card, as in
 * the Index layout's rows.
 */
fun cardRing(context: Context, radiusDp: Float = Tokens.CARD_RADIUS, thin: Boolean = false): LayerDrawable {
    val radius = context.dpf(radiusDp)
    val glow = GradientDrawable().apply {
        cornerRadius = radius + context.dpf(6f)
        setStroke(context.dp(2), Tokens.glow)
    }
    // The card sits RING_INSET inside these bounds; a thin ring keeps 2dp clear of it.
    val (strokePx, outside) = if (thin) context.dpf(1.5f).toInt().coerceAtLeast(2) to context.dpf(3.5f) else context.dp(3) to context.dpf(4f)
    val ring = GradientDrawable().apply {
        cornerRadius = radius + outside
        setStroke(strokePx, Tokens.ring)
    }
    return LayerDrawable(arrayOf(glow, ring)).apply {
        val i = context.dp(Tokens.RING_INSET) - outside.toInt()
        setLayerInset(1, i, i, i, i)
    }
}

/** Watch progress along the bottom edge; drawn once, never animated. */
@SuppressLint("ViewConstructor")
class CardProgress(context: Context, private val progress: Float) : View(context) {
    private val track = Paint().apply { color = 0x55FFFFFF }
    private val fill = Paint().apply { color = Tokens.accent }
    private val height = context.dpf(4f)
    private val margin = context.dpf(10f)
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val r = height / 2
        val bottom = getHeight() - margin
        rect.set(margin, bottom - height, width - margin, bottom)
        canvas.drawRoundRect(rect, r, r, track)
        rect.right = margin + (width - margin * 2) * progress
        canvas.drawRoundRect(rect, r, r, fill)
    }
}
