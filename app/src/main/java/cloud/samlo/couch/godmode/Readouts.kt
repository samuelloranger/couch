package cloud.samlo.couch.godmode

import android.graphics.Typeface
import android.text.format.Formatter
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import cloud.samlo.couch.R
import cloud.samlo.couch.data.SystemInfo
import cloud.samlo.couch.settings.SettingsContext
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp

/**
 * Live readings under godmode's help pane: memory, storage, network rates, time since start and
 * the TV's address, in monospace, refreshed every two seconds while the page has the screen.
 */
internal object Readouts {
    private const val EVERY_MS = 2000L

    fun attach(ctx: SettingsContext) {
        val c = ctx.activity
        val info = SystemInfo(c)
        val view = TextView(c).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12.5f
            setTextColor(Tokens.textMuted)
            setLineSpacing(0f, 1.3f)
        }
        ctx.layout.root.addView(view, FrameLayout.LayoutParams(c.dp(300), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = c.dp(Tokens.OVERSCAN_H + 600)
            bottomMargin = c.dp(Tokens.OVERSCAN_V + 10)
        })
        val tick = object : Runnable {
            override fun run() {
                if (!view.isAttachedToWindow) return
                if (c.hasWindowFocus()) view.text = text(ctx, info)
                view.postDelayed(this, EVERY_MS)
            }
        }
        view.post(tick)
    }

    private fun text(ctx: SettingsContext, info: SystemInfo): String {
        val c = ctx.activity
        val r = info.read()
        val rate = { b: Long? -> b?.let { Formatter.formatShortFileSize(c, it) + "/s" } ?: "–" }
        val up = r.uptimeMs / 60_000
        return listOf(
            c.getString(R.string.readout_ram, r.memoryUsedPercent),
            c.getString(R.string.readout_storage, Formatter.formatShortFileSize(c, r.storageFreeBytes)),
            c.getString(R.string.readout_net, rate(r.rxBytesPerSec), rate(r.txBytesPerSec)),
            c.getString(R.string.readout_uptime, up / (24 * 60), up / 60 % 24, up % 60),
            c.getString(R.string.readout_address, r.address ?: "–"),
        ).joinToString("\n")
    }
}
