package cloud.samlo.couch.index

import android.content.Context
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.widget.LinearLayout
import cloud.samlo.couch.R
import cloud.samlo.couch.data.SystemInfo
import cloud.samlo.couch.data.SystemReading
import cloud.samlo.couch.ui.Fonts
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp
import java.util.Locale

/**
 * The TV's own readings along the bottom: address, traffic, memory, storage, uptime, ADB.
 * Refreshes every few seconds while [start]ed; each item is one text view with a bold value.
 */
class SystemLine(context: Context) : LinearLayout(context) {
    private val info = SystemInfo(context)
    private val main = Handler(Looper.getMainLooper())
    private val items = List(6) { item() }

    /** How far the text views stand below the design's line box, for placing the line. */
    val inset: Int get() = items[0].inset
    private val tick = object : Runnable {
        override fun run() {
            show(info.read())
            main.postDelayed(this, REFRESH_MS)
        }
    }

    init {
        orientation = HORIZONTAL
        items.forEachIndexed { i, view ->
            addView(view, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { if (i > 0) marginStart = context.dp(20) })
        }
    }

    fun start() {
        main.removeCallbacks(tick)
        info.resetRates()
        tick.run()
    }

    fun stop() = main.removeCallbacks(tick)

    private fun show(r: SystemReading) {
        val c = context
        val link = when (r.transport) {
            NetworkCapabilities.TRANSPORT_ETHERNET -> c.getString(R.string.index_ethernet)
            NetworkCapabilities.TRANSPORT_WIFI -> c.getString(R.string.index_wifi) + (r.signalDbm?.let { ", ${minus(it)} dBm" } ?: "")
            -1 -> c.getString(R.string.index_offline)
            else -> ""
        }
        set(0, r.address ?: "", link)
        val rx = r.rxBytesPerSec
        val tx = r.txBytesPerSec
        if (rx == null || tx == null) {
            set(1, c.getString(R.string.index_rate, "–", "–"), c.getString(R.string.index_rate_unit))
        } else {
            val mega = maxOf(rx, tx) >= 1_000_000
            val scale = if (mega) 1_048_576.0 else 1024.0
            set(1, c.getString(R.string.index_rate, rate(rx / scale), rate(tx / scale)),
                c.getString(if (mega) R.string.index_rate_unit_mb else R.string.index_rate_unit))
        }
        set(2, c.getString(R.string.index_percent, r.memoryUsedPercent), c.getString(R.string.index_memory))
        set(3, c.getString(R.string.index_percent, r.storageUsedPercent),
            c.getString(R.string.index_storage, Formatter.formatShortFileSize(c, r.storageFreeBytes)))
        val minutes = r.uptimeMs / 60_000
        val uptime = if (minutes >= 24 * 60) {
            c.getString(R.string.index_days_hours, (minutes / (24 * 60)).toInt(), ((minutes / 60) % 24).toInt())
        } else {
            c.getString(R.string.index_hours_minutes, (minutes / 60).toInt(), (minutes % 60).toInt())
        }
        set(4, uptime, c.getString(R.string.index_uptime))
        set(5, if (r.adb) c.getString(R.string.index_adb) else "", c.getString(R.string.index_adb_on), Tokens.accent)
        items[5].visibility = if (r.adb) VISIBLE else GONE
    }

    /** "value label", the value bold and brighter. Unchanged text is left alone. */
    private fun set(i: Int, value: String, label: String, valueColor: Int = IndexTokens.footerValue) {
        val text = SpannableStringBuilder()
        if (value.isNotEmpty()) {
            text.append(value)
            text.setSpan(ForegroundColorSpan(valueColor), 0, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(TypefaceSpan(Fonts.hanken(context, 600)), 0, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (label.isNotEmpty()) text.append("  ")
        }
        text.append(label)
        if (items[i].text.toString() != text.toString()) items[i].text = text
    }

    private fun rate(value: Double) = String.format(Locale.getDefault(), if (value >= 100) "%.0f" else "%.1f", value)

    /** A real minus sign reads better than a hyphen. */
    private fun minus(value: Int) = if (value < 0) "−${-value}" else value.toString()

    private fun item() = IndexText(context, 10f, 500, IndexTokens.hours).apply {
        fontFeatureSettings = "tnum"
        maxLines = 1
    }

    private companion object {
        const val REFRESH_MS = 3_000L
    }
}
