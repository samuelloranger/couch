package cloud.samlo.couch.index

import android.annotation.SuppressLint
import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
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
 * The Index's Home Assistant row, top left: up to six lights and plugs, lights first, then a
 * line that sums them up or describes the focused one. States are read when the Index shows,
 * when focus comes up to the row and after each command, never on a timer; see [HassDevices].
 */
@SuppressLint("ViewConstructor")
class HomeRow(context: Context, hass: HomeAssistant) : LinearLayout(context), HomePill.Actions {
    private val devices = HassDevices(hass) { apply() }
    private val lights = group()
    private val plugs = group()
    private val divider = FlatView(context).apply { setBackgroundColor(0x14FFFFFF) }
    private val readout = IndexText(context, 11f, 500, Tokens.textMuted).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = context.dp(READOUT_W)
    }
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
        addView(readout, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(16) })
    }

    fun pills(): List<HomePill> = (0 until lights.childCount).map { lights.getChildAt(it) as HomePill } +
        (0 until plugs.childCount).map { plugs.getChildAt(it) as HomePill }

    /** Where focus lands coming up from the apps: the pill last used, else the first. */
    fun entry(): View? = lastFocused?.takeIf { it.parent != null } ?: pills().firstOrNull()

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
    }

    override fun step(pill: HomePill, direction: Int) = devices.step(pill.entityId, direction)

    /** Leaves dimming, sending the last level at once. */
    fun stopDimming() {
        val pill = dimming ?: return
        dimming = null
        pill.dimming = false
        devices.stopDimming()
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
                    post { showReadout() }
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
        }
        showReadout()
    }

    private fun showReadout() {
        val focused = pills().firstOrNull { it.isFocused }
        readout.text = if (focused != null) describe(focused) else summary()
    }

    private fun describe(pill: HomePill): CharSequence {
        val d = devices[pill.entityId] ?: return ""
        return when {
            !devices.reachable -> warn(context.getString(R.string.home_unreachable))
            devices.failed == d.id -> warn(context.getString(R.string.home_failed))
            !d.available -> warn(context.getString(R.string.home_state_offline))
            dimming === pill -> gold(context.getString(R.string.home_dimming, d.brightness))
            d.on && d.dimmable -> context.getString(R.string.home_brightness_hint, d.brightness)
            d.on -> context.getString(R.string.home_state_on)
            else -> context.getString(R.string.home_state_off)
        }
    }

    private fun summary(): CharSequence {
        val shown = devices.ids.mapNotNull { devices[it] }
        if (shown.isEmpty() && devices.reachable) return ""
        if (!devices.reachable) return warn(context.getString(R.string.home_unreachable))
        val on = shown.count { it.available && it.on }
        val offline = shown.count { !it.available }
        val text = SpannableStringBuilder(
            if (on == 0) context.getString(R.string.home_all_off) else resources.getQuantityString(R.plurals.home_on, on, on),
        )
        if (offline > 0) {
            text.append(" · ")
            text.append(warn(resources.getQuantityString(R.plurals.home_offline, offline, offline)))
        }
        return text
    }

    private fun warn(text: String) = colored(text, Tokens.warning)

    private fun gold(text: String) = colored(text, HomePill.GOLD)

    private fun colored(text: String, color: Int) = SpannableStringBuilder(text).apply {
        setSpan(ForegroundColorSpan(color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun group() = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
    }

    private companion object {
        const val PILL_H = 30
        const val GAP = 8
        const val READOUT_W = 190
    }
}
