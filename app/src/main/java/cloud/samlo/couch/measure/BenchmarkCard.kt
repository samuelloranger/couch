package cloud.samlo.couch.measure

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import cloud.samlo.couch.R
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf
import cloud.samlo.couch.update.Versions
import org.json.JSONObject

/**
 * The benchmark's result over the home screen: the three percentiles with how each moved since the
 * last run, a histogram with the 16.7 ms budget marked, and what it ran on. Any key closes it.
 *
 * In [fps] the percentiles read as rates, so they also change sides: the slow tail (p99) is the
 * lowest rate, which is what "1% low" names, and more is better.
 */
object BenchmarkCard {
    fun show(activity: Activity, r: Benchmark.Result, last: JSONObject?, fps: Boolean): View {
        val c = activity
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(c.dp(26), c.dp(22), c.dp(26), c.dp(22))
            background = GradientDrawable().apply {
                cornerRadius = c.dpf(16f)
                // Opaque: the home screen's bright app cards showed through a translucent card.
                setColor(0xFF0E1412.toInt())
                setStroke(c.dp(1), Tokens.GODMODE_COLOR)
            }
        }
        card.addView(text(c, c.getString(R.string.bench_kicker, c.getString(R.string.bench_index), r.presses), 11f, Tokens.textMuted, mono = true))
        val title = when {
            last == null -> R.string.bench_first
            r.p90 < last.optInt("p90") -> R.string.bench_better
            r.p90 > last.optInt("p90") -> R.string.bench_worse
            else -> R.string.bench_same
        }
        card.addView(text(c, c.getString(title), 20f, Tokens.textPrimary).apply { setPadding(0, c.dp(2), 0, c.dp(14)) })
        val hz = c.windowManager.defaultDisplay?.refreshRate?.takeIf { it > 1f } ?: 60f
        val names = if (fps) {
            listOf(R.string.bench_low_1, R.string.bench_low_10, R.string.bench_median)
        } else {
            listOf(R.string.bench_p50, R.string.bench_p90, R.string.bench_p99)
        }
        val keys = if (fps) listOf("p99", "p90", "p50") else listOf("p50", "p90", "p99")
        val values = if (fps) listOf(r.p99, r.p90, r.p50) else listOf(r.p50, r.p90, r.p99)
        val stats = LinearLayout(c)
        values.forEachIndexed { i, msValue ->
            val shown = if (fps) fps(msValue.toFloat(), hz) else msValue
            val delta = last?.optInt(keys[i])?.let { was ->
                if (fps) shown - fps(was.toFloat(), hz) else msValue - was
            }
            stats.addView(
                stat(c, c.getString(names[i]), shown, delta, fps),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = c.dp(10) },
            )
        }
        card.addView(stats)
        card.addView(Histogram(c, r.histogram), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, c.dp(56)).apply { topMargin = c.dp(16) })
        card.addView(text(c, c.getString(R.string.bench_meta, r.slow, r.frames, Build.MODEL, Build.VERSION.RELEASE, Versions.installed(c)), 11f, Tokens.textMuted, mono = true).apply {
            setPadding(0, c.dp(10), 0, 0)
        })
        card.addView(text(c, c.getString(R.string.bench_close), 13f, Tokens.textMuted).apply { setPadding(0, c.dp(12), 0, 0) })
        val root = c.window.decorView as ViewGroup
        root.addView(card, FrameLayout.LayoutParams(c.dp(520), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        return card
    }


    private fun stat(c: Context, name: String, value: Int, delta: Int?, fps: Boolean) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(c.dp(14), c.dp(10), c.dp(14), c.dp(10))
        background = GradientDrawable().apply {
            cornerRadius = c.dpf(10f)
            setColor(Tokens.background)
        }
        addView(text(c, name, 11f, Tokens.textMuted, mono = true))
        addView(text(c, c.getString(if (fps) R.string.bench_fps else R.string.bench_ms, value), 26f, Tokens.textPrimary, mono = true))
        if (delta != null) {
            val sign = if (delta > 0) "+" else if (delta < 0) "−" else "±"
            // More frames a second is better; more milliseconds is worse.
            val better = if (fps) delta > 0 else delta < 0
            val colour = if (delta == 0 || better) Tokens.GODMODE_COLOR else 0xFFFBBF24.toInt()
            val unit = if (fps) R.string.bench_delta_fps else R.string.bench_delta
            addView(text(c, c.getString(unit, sign, kotlin.math.abs(delta)), 11.5f, colour, mono = true))
        }
    }

    private fun text(c: Context, s: String, size: Float, color: Int, mono: Boolean = false) = TextView(c).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (mono) typeface = Typeface.MONOSPACE
    }

    /** Frame times in 2 ms buckets; buckets past the 16.7 ms budget are amber. */
    @SuppressLint("ViewConstructor")
    private class Histogram(context: Context, private val buckets: IntArray) : View(context) {
        private val ok = Paint().apply { color = Tokens.GODMODE_COLOR }
        private val slow = Paint().apply { color = 0xFFFBBF24.toInt() }

        override fun onDraw(canvas: Canvas) {
            val most = (buckets.maxOrNull() ?: 0).coerceAtLeast(1)
            val w = width.toFloat() / buckets.size
            buckets.forEachIndexed { i, n ->
                val h = (height * n / most.toFloat()).coerceAtLeast(if (n > 0) 2f else 1f)
                canvas.drawRect(i * w + 1, height - h, (i + 1) * w - 1, height.toFloat(), if (i >= 8) slow else ok)
            }
        }
    }
}
