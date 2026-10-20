package cloud.samlo.couch.index

import android.annotation.SuppressLint
import android.content.Context
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import cloud.samlo.couch.ui.Fonts
import kotlin.math.roundToInt

/**
 * Text set like the design: Hanken Grotesk at [lineHeight] times its size between lines.
 *
 * The font's own line box is 1.3 em, taller than the design's (usually 1.0), so a plain text view
 * pushes what follows down. Trimming the view would clip descenders, so it keeps its height and
 * reports [inset], the extra above (and below) the design's box; layouts subtract it from their
 * margins with [stack].
 */
@SuppressLint("ViewConstructor")
class IndexText(context: Context, sizeSp: Float, weight: Int, color: Int, lineHeight: Float = 1f) : TextView(context) {
    /** Pixels this view stands taller than the design's line box, at its top and again at its bottom. */
    val inset: Int

    init {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = Fonts.hanken(context, weight)
        setTextColor(color)
        includeFontPadding = false
        // Fallback fonts' taller metrics would undo the line height.
        isFallbackLineSpacing = false
        val target = (textSize * lineHeight).roundToInt()
        setLineHeight(target)
        val metrics = paint.fontMetricsInt
        inset = ((metrics.descent - metrics.ascent) - target) / 2
    }

    /** [params] with the design's [top] and [bottom] gaps, corrected for [inset]. */
    fun <T : ViewGroup.MarginLayoutParams> stack(params: T, top: Int = 0, bottom: Int = 0): T = params.apply {
        topMargin = top - inset
        bottomMargin = bottom - inset
    }
}
