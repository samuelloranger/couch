package cloud.samlo.couch.index

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import cloud.samlo.couch.R
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.hass.HassDevice
import cloud.samlo.couch.hass.HassDevices
import cloud.samlo.couch.hass.HassJson
import cloud.samlo.couch.hass.HomeAssistant
import cloud.samlo.couch.ui.FlatView
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp

/**
 * The Index's Home Assistant row, top left: up to six lights and plugs, lights first, each pill
 * showing its own state. States are read when the Index shows, when focus comes up to the row and
 * after each command, never on a timer; see [HassDevices].
 */
@SuppressLint("ViewConstructor")
class HomeRow(context: Context, hass: HomeAssistant) : LinearLayout(context), HomePill.Actions {
    private val devices = HassDevices(hass) { apply() }
    private val lights = group()
    private val plugs = group()
    private val divider = FlatView(context).apply { setBackgroundColor(0x14FFFFFF) }
    private var names: Map<String, String> = emptyMap()
    private var lastFocused: HomePill? = null
    var dimming: HomePill? = null
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
        // Room for a focused pill's scale and ring: drawn past the pill, never cut by this row.
        val inset = context.dp(Tokens.RING_INSET)
        setPadding(inset, inset, inset, inset)
        clipChildren = false
        clipToPadding = false
        addView(lights, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(divider, LayoutParams(context.dp(1), context.dp(16)).apply {
            marginStart = context.dp(12)
            marginEnd = context.dp(12)
        })
        addView(plugs, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    /**
     * Fits the row in [available] pixels, padding included, cutting the longest names to a common
     * width. Worked out from the full widths, so every layout pass lands on the same answer.
     */
    fun fit(available: Int) {
        val pills = pills()
        if (pills.isEmpty()) return
        val dividerW = if (divider.visibility == VISIBLE) context.dp(1 + 24) else 0
        val fixed = paddingLeft + paddingRight + dividerW + context.dp(GAP) * (lights.childCount.coerceAtLeast(1) - 1 + plugs.childCount.coerceAtLeast(1) - 1)
        val natural = fixed + pills.sumOf { it.naturalWidth() }
        val excess = natural - available
        val cap = if (excess <= 0) Int.MAX_VALUE else labelCap(pills.map { it.labelWidth() }, excess)
        pills.forEach { it.labelLimit = cap }
    }

    fun pills(): List<HomePill> = (0 until lights.childCount).map { lights.getChildAt(it) as HomePill } +
        (0 until plugs.childCount).map { plugs.getChildAt(it) as HomePill }

    /** Where focus lands coming up from the apps: the pill last used, else the first. */
    fun entry(): View? = lastFocused?.takeIf { it.parent != null } ?: pills().firstOrNull()

    /** Streams changes while the Index shows; see [HassDevices.live]. */
    fun live(on: Boolean) = devices.live(on)

    fun bind(config: Config) {
        if (devices.use(config.homeAssistantUrl, config.homeDevices) || config.homeNames != names) {
            names = config.homeNames
            rebuild()
        }
        visibility = if (devices.ids.isEmpty()) GONE else VISIBLE
        devices.refresh()
    }

    override fun press(pill: HomePill) {
        if (dimming === pill) return stopDimming()
        devices.toggle(pill.entityId)
    }

    override fun hold(pill: HomePill) {
        if (!devices.canDim(pill.entityId)) return
        dimming = pill
        pill.dimming = true
        devices.startDimming(pill.entityId)
        apply()
    }

    override fun step(pill: HomePill, direction: Int) = devices.step(pill.entityId, direction)

    /** Leaves dimming, sending the last level at once. */
    fun stopDimming() {
        val pill = dimming ?: return
        dimming = null
        pill.dimming = false
        devices.stopDimming()
        apply()
    }

    /** Back ends dimming (OK does too, through [press]); true when the key was used for that. */
    fun consumesKey(event: KeyEvent): Boolean {
        if (dimming == null || event.keyCode != KeyEvent.KEYCODE_BACK) return false
        if (event.action == KeyEvent.ACTION_UP) stopDimming()
        return true
    }

    private fun rebuild() {
        lights.removeAllViews()
        plugs.removeAllViews()
        lastFocused = null
        devices.ids.forEach { id ->
            val pill = HomePill(context, id, this).apply {
                setOnFocusChangeListener { v, has ->
                    if (has) {
                        lastFocused = v as HomePill
                        devices.refresh()
                    } else if (this@HomeRow.dimming === v) {
                        stopDimming()
                    }
                }
            }
            val into = if (id.startsWith(HassDevice.LIGHT)) lights else plugs
            into.addView(pill, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(PILL_H)).apply {
                if (into.childCount > 0) marginStart = context.dp(GAP)
            })
        }
        divider.visibility = if (lights.childCount > 0 && plugs.childCount > 0) VISIBLE else GONE
        apply()
    }

    private fun apply() {
        pills().forEach { pill ->
            val d = devices[pill.entityId]
            pill.label = names[pill.entityId]?.takeIf { it.isNotBlank() }
                ?: HassJson.shortName(d?.name ?: pill.entityId.substringAfter('.'))
            pill.device = d
            pill.reachable = devices.reachable
            pill.status = status(pill, d)
            pill.statusColor = when {
                d == null -> Tokens.textMuted
                !devices.reachable || devices.failed == d.id || !d.available -> Tokens.warning
                dimming === pill || (d.on && d.isLight) -> HomePill.GOLD
                d.on -> Tokens.accent
                else -> Tokens.textMuted
            }
        }
    }

    private fun status(pill: HomePill, d: HassDevice?): String = when {
        d == null -> ""
        !devices.reachable -> context.getString(R.string.home_state_unreachable)
        devices.failed == d.id -> context.getString(R.string.home_failed_short)
        !d.available -> context.getString(R.string.home_state_offline)
        dimming === pill -> context.getString(R.string.home_dimming, d.brightness)
        // Switched on but not reported yet: no level to show.
        d.on && d.dimmable && d.brightness > 0 -> context.getString(R.string.home_percent, d.brightness)
        d.on -> context.getString(R.string.home_state_on)
        else -> context.getString(R.string.home_state_off)
    }

    /** The widest name width that saves [excess] pixels across names of [widths]. */
    private fun labelCap(widths: List<Int>, excess: Int): Int {
        var lo = 0
        var hi = widths.maxOrNull() ?: 0
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (widths.sumOf { (it - mid).coerceAtLeast(0) } >= excess) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun group() = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
    }

    private companion object {
        const val PILL_H = 36
        const val GAP = 8
    }
}
