package cloud.samlo.couch.index

import android.content.Context
import cloud.samlo.couch.R
import cloud.samlo.couch.hass.Appliances
import cloud.samlo.couch.hass.HassDevice
import cloud.samlo.couch.ui.Tokens

/** What a Home Assistant device's second line says, and in which colour, wherever it shows. */
object HomeStatus {
    private const val MINUTE = 60_000L

    fun text(
        context: Context,
        d: HassDevice?,
        reachable: Boolean = true,
        failed: Boolean = false,
        dimming: Boolean = false,
        finishAt: Long? = null,
        now: Long = System.currentTimeMillis(),
    ): String = when {
        d == null -> ""
        !reachable -> context.getString(R.string.home_state_unreachable)
        failed -> context.getString(R.string.home_failed_short)
        !d.available -> context.getString(R.string.home_state_offline)
        d.isAppliance -> appliance(context, d, finishAt, now)
        dimming -> context.getString(R.string.home_dimming, d.brightness)
        // Switched on but not reported yet: no level to show.
        d.on && d.dimmable && d.brightness > 0 -> context.getString(R.string.home_percent, d.brightness)
        d.on -> context.getString(R.string.home_state_on)
        else -> context.getString(R.string.home_state_off)
    }

    fun color(d: HassDevice?, reachable: Boolean = true, failed: Boolean = false, dimming: Boolean = false): Int = when {
        d == null -> Tokens.textMuted
        !reachable || failed || !d.available -> Tokens.warning
        d.isAppliance -> when (Appliances.phase(d.raw)) {
            Appliances.Phase.RUNNING -> Tokens.accent
            Appliances.Phase.DONE -> Tokens.textPrimary
            else -> Tokens.textMuted
        }
        dimming || (d.on && d.isLight) -> HomePill.GOLD
        d.on -> Tokens.accent
        else -> Tokens.textMuted
    }

    private fun appliance(context: Context, d: HassDevice, finishAt: Long?, now: Long): String {
        val phase = when (Appliances.phase(d.raw)) {
            Appliances.Phase.RUNNING -> context.getString(R.string.home_running)
            Appliances.Phase.PAUSED -> context.getString(R.string.home_paused)
            Appliances.Phase.DELAYED -> context.getString(R.string.home_delayed)
            Appliances.Phase.DONE -> context.getString(R.string.home_done)
            Appliances.Phase.IDLE -> context.getString(R.string.home_idle)
            Appliances.Phase.OTHER -> Appliances.readable(d.raw)
        }
        if (Appliances.phase(d.raw) != Appliances.Phase.RUNNING || finishAt == null) return phase
        val minutes = ((finishAt - now + MINUTE - 1) / MINUTE).toInt().coerceAtLeast(1)
        return if (minutes < 60) {
            context.getString(R.string.home_left_minutes, phase, minutes)
        } else {
            context.getString(R.string.home_left_hours, phase, minutes / 60, minutes % 60)
        }
    }
}
