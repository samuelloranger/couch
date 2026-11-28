package cloud.samlo.couch.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity

/**
 * One focus language, two forms. Cards and tiles: a 2dp ring, the accent glow and a scale
 * ([scale]). Rows (lists, settings, menus, panels): a plate of white at [PLATE] and the
 * [MARKER_W]x[MARKER_H]dp accent marker, no scale. Focus never changes a view's size, so it
 * never pushes a neighbour.
 */
object Focus {
    /** White at 12%: 1.3x the row beneath it on the page background and on a surface. */
    const val PLATE = 0x1FFFFFFF

    /** The plate while the row is selected but focus is elsewhere. */
    const val PLATE_QUIET = 0x0FFFFFFF

    const val MARKER_W = 2
    const val MARKER_H = 14
    const val RING_W = 2

    /** Small icons, tiles and posters grow by this; cards [WIDE_DP] and wider by [SCALE_WIDE]. */
    const val SCALE_SMALL = 1.08f
    const val SCALE_WIDE = 1.05f
    const val WIDE_DP = 96

    fun scale(widthDp: Int): Float = if (widthDp >= WIDE_DP) SCALE_WIDE else SCALE_SMALL

    /** The plate with its marker, for a row's focused (or [quiet]) state. */
    fun plate(context: Context, radiusDp: Float = ROW_RADIUS, quiet: Boolean = false): LayerDrawable {
        val plate = GradientDrawable().apply {
            cornerRadius = context.dpf(radiusDp)
            setColor(if (quiet) PLATE_QUIET else PLATE)
        }
        val marker = GradientDrawable().apply {
            cornerRadius = context.dpf(1f)
            setColor(if (quiet) Tokens.graphicIdle else Tokens.accent)
        }
        return LayerDrawable(arrayOf(plate, marker)).apply {
            setLayerSize(1, context.dp(MARKER_W), context.dp(MARKER_H))
            setLayerGravity(1, Gravity.START or Gravity.CENTER_VERTICAL)
            setLayerInsetStart(1, context.dp(MARKER_INSET))
        }
    }

    /** A row's background: the plate while focused, nothing otherwise. Call again after the accent changes. */
    fun rowBackground(context: Context, radiusDp: Float = ROW_RADIUS): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), plate(context, radiusDp))
        addState(intArrayOf(), GradientDrawable().apply { cornerRadius = context.dpf(radiusDp) })
    }

    /**
     * A small tile's or icon's ring, drawn inside its own bounds: the accent glow outermost, the
     * white ring just within. Pair it with [scale].
     */
    fun tileRing(context: Context, radiusDp: Float, fill: Int = 0): LayerDrawable {
        val inset = context.dp(RING_W)
        val glow = GradientDrawable().apply {
            cornerRadius = context.dpf(radiusDp)
            setStroke(context.dp(RING_W), Tokens.glow)
        }
        val ring = GradientDrawable().apply {
            cornerRadius = context.dpf(radiusDp - 1f)
            setColor(fill)
            setStroke(context.dp(RING_W), Tokens.ring)
        }
        return LayerDrawable(arrayOf(glow, ring)).apply { setLayerInset(1, inset, inset, inset, inset) }
    }

    /** The white ring a text field wears while it has focus. */
    fun ring(context: Context, radiusDp: Float, fill: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = context.dpf(radiusDp)
        setColor(fill)
        setStroke(context.dp(RING_W), Tokens.ring)
    }

    const val ROW_RADIUS = 6f
    private const val MARKER_INSET = 4
}
