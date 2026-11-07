package cloud.samlo.couch.index

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.hass.HassDevice
import cloud.samlo.couch.hass.HassDevices
import cloud.samlo.couch.hass.HassJson
import cloud.samlo.couch.hass.HomeAssistant
import cloud.samlo.couch.ui.FlatView
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp

/**
 * The Index's Home Assistant row, top left: up to six devices, lights, then plugs, then
 * appliances, each pill showing its own state. States are read when the Index shows, when focus comes up to the row and
 * after each command, never on a timer; see [HassDevices].
 */
@SuppressLint("ViewConstructor")
class HomeRow(context: Context, hass: HomeAssistant) : LinearLayout(context), HomePill.Actions {
    private val devices = HassDevices(hass) { apply() }
    /** Lights, plugs and appliances, each in its own group with a rule between them. */
    private val groups = List(3) { group() }
    private val dividers = List(2) { FlatView(context).apply { setBackgroundColor(0x14FFFFFF) } }
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
        groups.forEachIndexed { i, group ->
            if (i > 0) {
                addView(dividers[i - 1], LayoutParams(context.dp(1), context.dp(16)).apply {
                    marginStart = context.dp(DIVIDER_SPACE)
                    marginEnd = context.dp(DIVIDER_SPACE)
                })
            }
            addView(group, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
    }

    /**
     * Fits the row in [available] pixels, padding included, cutting the longest names to a common
     * width. Worked out from the full widths, so every layout pass lands on the same answer.
     */
    fun fit(available: Int) {
        val pills = pills()
        if (pills.isEmpty()) return
        val rules = dividers.count { it.visibility == VISIBLE } * context.dp(1 + 2 * DIVIDER_SPACE)
        val gaps = groups.sumOf { (it.childCount - 1).coerceAtLeast(0) } * context.dp(GAP)
        val fixed = paddingLeft + paddingRight + rules + gaps
        val natural = fixed + pills.sumOf { it.naturalWidth() }
        val excess = natural - available
        val cap = if (excess <= 0) Int.MAX_VALUE else labelCap(pills.map { it.labelWidth() }, excess)
        pills.forEach { it.labelLimit = cap }
    }

    fun pills(): List<HomePill> = groups.flatMap { g -> (0 until g.childCount).map { g.getChildAt(it) as HomePill } }

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
        groups.forEach { it.removeAllViews() }
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
            val into = groups[HassDevice.order(id)]
            into.addView(pill, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(PILL_H)).apply {
                if (into.childCount > 0) marginStart = context.dp(GAP)
            })
        }
        // A rule only between two groups that both have pills.
        val filled = groups.map { it.childCount > 0 }
        dividers.forEachIndexed { i, rule ->
            val before = filled.take(i + 1).any { it }
            rule.visibility = if (before && filled[i + 1]) VISIBLE else GONE
        }
        apply()
    }

    private fun apply() {
        pills().forEach { pill ->
            val d = devices[pill.entityId]
            pill.label = names[pill.entityId]?.takeIf { it.isNotBlank() }
                ?: HassJson.shortName(d?.name ?: pill.entityId.substringAfter('.'))
            pill.device = d
            pill.reachable = devices.reachable
            val failed = devices.failed == pill.entityId
            pill.status = HomeStatus.text(context, d, devices.reachable, failed, dimming === pill, devices.finish(pill.entityId))
            pill.statusColor = HomeStatus.color(d, devices.reachable, failed, dimming === pill)
        }
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
        const val DIVIDER_SPACE = 12
    }
}
