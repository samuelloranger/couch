package cloud.samlo.couch.index

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import cloud.samlo.couch.R
import cloud.samlo.couch.data.Usage
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf

/**
 * Three figures under the channel row: the last seven days (total and one bar per day), when the
 * app was last opened, and how often. Without usage access, one line says how to get them.
 */
class StatsBar(context: Context) : LinearLayout(context) {
    private val weekValue = value()
    private val bars = List(Usage.DAYS) { View(context) }
    private val barFills = List(Usage.DAYS) { GradientDrawable().apply { cornerRadius = context.dpf(1.5f) } }
    private val letters = List(Usage.DAYS) { text(6.5f, 500, IndexTokens.hours) }
    private val lastValue = value()
    private val lastSub = sub()
    private val opensValue = value()
    private val opensSub = sub()
    private val columns: LinearLayout
    private val missing = text(11f, 500, Tokens.textMuted, 1.4f).apply { maxLines = 3 }

    init {
        orientation = VERTICAL
        columns = LinearLayout(context).apply { orientation = HORIZONTAL }
        columns.addView(column(R.string.index_week, weekRow(), null), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.25f))
        columns.addView(column(R.string.index_last_opened, lastValue, lastSub), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = context.dp(20)
        })
        columns.addView(column(R.string.index_opens, opensValue, opensSub), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = context.dp(20)
        })
        addView(columns)
        missing.text = context.getString(R.string.index_no_usage)
        addView(missing, missing.stack(LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)))
    }

    fun show(entry: IndexEntry, usageKnown: Boolean, now: Long, clock24h: Boolean) {
        columns.visibility = if (usageKnown) VISIBLE else GONE
        missing.visibility = if (usageKnown) GONE else VISIBLE
        if (!usageKnown) return
        val usage = entry.usage
        val opens = usage?.opens ?: 0
        weekValue.text = Formats.long(context, usage?.weekMs ?: 0L, opens > 0)
        val perDay = usage?.perDayMs ?: LongArray(Usage.DAYS)
        val max = maxOf(60 * 60_000L, perDay.max())
        val full = context.dpf(22f)
        perDay.forEachIndexed { i, ms ->
            val height = maxOf(context.dp(1.5f), (full * ms / max).toInt())
            barFills[i].setColor(if (i == Usage.DAYS - 1) Tokens.accent else IndexTokens.barOff)
            // Only a bar whose height changed needs a layout pass.
            if (bars[i].layoutParams.height != height) {
                bars[i].layoutParams.height = height
                bars[i].requestLayout()
            }
        }
        Formats.dayLetters(now).forEachIndexed { i, letter -> letters[i].text = letter }
        val (last, lastLine) = Formats.lastOpened(context, usage?.lastUsed ?: 0L, now, clock24h)
        lastValue.text = last
        lastSub.text = lastLine
        opensValue.text = if (opens > 0) opens.toString() else context.getString(R.string.index_none)
        opensSub.text = when {
            entry.rank >= 0 -> Formats.rank(context, entry.rank)
            opens > 0 -> context.getString(R.string.index_opened_briefly)
            else -> context.getString(R.string.index_not_opened)
        }
    }

    private fun weekRow() = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.BOTTOM
        addView(weekValue)
        val chart = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        for (i in 0 until Usage.DAYS) {
            chart.addView(LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                bars[i].background = barFills[i]
                addView(bars[i], LayoutParams(LayoutParams.MATCH_PARENT, context.dp(1.5f)))
                addView(letters[i], letters[i].stack(LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT), top = context.dp(3)))
            }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) marginStart = context.dp(3) })
        }
        // Bottoms line up on the design's line box, not on the taller text view.
        addView(chart, LayoutParams(0, context.dp(31), 1f).apply {
            marginStart = context.dp(11)
            bottomMargin = weekValue.inset
        })
    }

    private fun column(label: Int, valueView: View, subView: IndexText?) = LinearLayout(context).apply {
        orientation = VERTICAL
        val heading = text(10f, 500, Tokens.textMuted).apply { text = context.getString(label) }
        addView(heading, heading.stack(LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)))
        val gap = context.dp(5)
        if (valueView is IndexText) {
            addView(valueView, valueView.stack(LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT), top = gap))
        } else {
            // The week row stretches so its bars share the column's width. Its chart is taller than
            // the gap plus the figure, so the bars start right under the heading.
            addView(valueView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        subView?.let { addView(it, it.stack(LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT), top = gap)) }
    }

    private fun value() = text(22f, 700, Tokens.textPrimary).apply {
        letterSpacing = -0.02f
        fontFeatureSettings = "tnum"
    }

    private fun sub() = text(9.5f, 400, Tokens.textMuted)

    private fun text(sizeSp: Float, weight: Int, color: Int, lineHeight: Float = 1f) =
        IndexText(context, sizeSp, weight, color, lineHeight).apply { maxLines = 1 }

    private fun Context.dp(value: Float) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
