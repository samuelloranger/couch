package cloud.samlo.couch.index

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import cloud.samlo.couch.R
import cloud.samlo.couch.data.Usage
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf

/**
 * The week under the channel row: its total as one figure, a line saying when the app was last
 * opened, and a bar per day with today in the accent. Without usage access, one line says how to
 * get them.
 */
class StatsBar(context: Context) : LinearLayout(context) {
    private val weekValue = text(24f, 600, Tokens.textPrimary).apply {
        letterSpacing = -0.02f
        fontFeatureSettings = "tnum"
    }
    private val weekLine = text(12f, 400, Tokens.textMuted).apply { ellipsize = TextUtils.TruncateAt.END }
    private val bars = List(Usage.DAYS) { View(context) }
    private val barFills = List(Usage.DAYS) { GradientDrawable().apply { cornerRadius = context.dpf(2f) } }
    private val letters = List(Usage.DAYS) { text(10f, 500, Tokens.textMuted) }
    private val figures: LinearLayout
    private val missing = text(12f, 500, Tokens.textMuted, 1.4f).apply { maxLines = 3 }

    init {
        orientation = VERTICAL
        val total = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(weekValue, weekValue.stack(LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)))
            addView(weekLine, weekLine.stack(LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT), top = context.dp(8)))
        }
        figures = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(total, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(chart(), LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(BAR_MAX + LETTER_ROW)))
        }
        addView(figures, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        missing.text = context.getString(R.string.index_no_usage)
        addView(missing, missing.stack(LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)))
    }

    fun show(entry: IndexEntry, usageKnown: Boolean, now: Long, clock24h: Boolean) {
        figures.visibility = if (usageKnown) VISIBLE else GONE
        missing.visibility = if (usageKnown) GONE else VISIBLE
        if (!usageKnown) return
        val usage = entry.usage
        val opens = usage?.opens ?: 0
        // A week without use is a real zero, not a missing value: a dash this size reads as loading.
        val weekMs = usage?.weekMs ?: 0L
        weekValue.text = if (weekMs <= 0L && opens == 0) context.getString(R.string.index_minutes, 0) else Formats.long(context, weekMs, opens > 0)
        val last = Formats.lastOpened(context, usage?.lastUsed ?: 0L, now, clock24h)
        weekLine.text = if (last == null) {
            context.getString(R.string.index_not_opened)
        } else {
            context.getString(R.string.index_week_line, context.getString(R.string.index_week), last)
        }
        val perDay = usage?.perDayMs ?: LongArray(Usage.DAYS)
        val max = maxOf(60 * 60_000L, perDay.max())
        val full = context.dpf(BAR_MAX.toFloat())
        perDay.forEachIndexed { i, ms ->
            val height = maxOf(context.dp(2), (full * ms / max).toInt())
            barFills[i].setColor(if (i == Usage.DAYS - 1) Tokens.accent else IndexTokens.barOff)
            // Only a bar whose height changed needs a layout pass.
            if (bars[i].layoutParams.height != height) {
                bars[i].layoutParams.height = height
                bars[i].requestLayout()
            }
        }
        Formats.dayLetters(now).forEachIndexed { i, letter ->
            letters[i].text = letter
            letters[i].setTextColor(if (i == Usage.DAYS - 1) Tokens.textPrimary else Tokens.textMuted)
        }
    }

    /** Seven bars standing on one line, each over its day's letter. */
    private fun chart() = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.BOTTOM
        for (i in 0 until Usage.DAYS) {
            addView(LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                bars[i].background = barFills[i]
                addView(bars[i], LayoutParams(context.dp(BAR_W), context.dp(2)))
                addView(letters[i], letters[i].stack(LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT), top = context.dp(5)))
            }, LayoutParams(context.dp(BAR_W), LayoutParams.MATCH_PARENT).apply { if (i > 0) marginStart = context.dp(BAR_GAP) })
        }
    }

    private fun text(sizeSp: Float, weight: Int, color: Int, lineHeight: Float = 1f) =
        IndexText(context, sizeSp, weight, color, lineHeight).apply { maxLines = 1 }

    private companion object {
        const val BAR_W = 22
        const val BAR_GAP = 6
        const val BAR_MAX = 48
        /** The gap under the bars plus a day letter's line. */
        const val LETTER_ROW = 15
    }
}
