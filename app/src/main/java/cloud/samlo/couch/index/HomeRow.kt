package cloud.samlo.couch.index

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
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
import cloud.samlo.couch.hass.HassJson
import cloud.samlo.couch.hass.HomeAssistant
import cloud.samlo.couch.ui.FlatView
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp
import java.util.concurrent.Executors

/**
 * The Index's Home Assistant row, top left: up to six lights and plugs, lights first, then a
 * line that sums them up or describes the focused one.
 *
 * Commands are optimistic: a pill changes at the press and Home Assistant is told behind it; if it
 * refuses, the pill goes back. States are read when the Index shows, when focus comes up to the
 * row and after each command, never on a timer. Each device carries a version bumped by every
 * command, so a read that left before the press can't undo it.
 */
@SuppressLint("ViewConstructor")
class HomeRow(context: Context, private val hass: HomeAssistant) : LinearLayout(context), HomePill.Actions {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "couch-hass").apply { isDaemon = true } }
    private val lights = group()
    private val plugs = group()
    private val divider = FlatView(context).apply { setBackgroundColor(0x14FFFFFF) }
    private val readout = IndexText(context, 11f, 500, Tokens.textMuted).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = context.dp(READOUT_W)
    }

    private var url = ""
    private var ids: List<String> = emptyList()
    private var names: Map<String, String> = emptyMap()
    private val known = HashMap<String, HassDevice>()
    private val versions = HashMap<String, Int>()
    private var reachable = true
    private var readAt = 0L
    /** The device whose last command Home Assistant refused, until the next press or read. */
    private var failed: String? = null
    private var lastFocused: HomePill? = null
    var dimming: HomePill? = null
        private set
    private val sendBrightness = Runnable { dimming?.let { sendBrightness(it.entityId) } }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
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
        url = HassJson.baseUrl(config.homeAssistantUrl)
        val wanted = if (hass.connected(url)) config.homeDevices.filter { HassDevice.supported(it) } else emptyList()
        if (wanted != ids || config.homeNames != names) {
            ids = wanted
            names = config.homeNames
            rebuild()
        }
        visibility = if (ids.isEmpty()) GONE else VISIBLE
        refresh()
    }

    /** Reads every device again unless that happened a moment ago. */
    fun refresh() {
        if (ids.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (now - readAt < MIN_READ_MS) return
        readAt = now
        val asked = ids.associateWith { versions[it] ?: 0 }
        val base = url
        worker.execute {
            val read = asked.keys.associateWith { hass.state(base, it) }
            post {
                reachable = read.values.any { it != null }
                read.forEach { (id, d) -> if (d != null && (versions[id] ?: 0) == asked[id]) known[id] = d }
                failed = null
                apply()
            }
        }
    }

    override fun press(pill: HomePill) {
        if (dimming === pill) return stopDimming()
        val id = pill.entityId
        val before = known[id] ?: return refresh()
        if (!reachable || !before.available) return
        known[id] = before.copy(on = !before.on)
        failed = null
        apply()
        val version = bump(id)
        val base = url
        worker.execute {
            val ok = hass.toggle(base, id)
            val after = hass.state(base, id)
            post { settle(id, version, ok, before, after) }
        }
    }

    override fun hold(pill: HomePill) {
        val d = known[pill.entityId] ?: return
        if (!reachable || !d.available || !d.dimmable) return
        dimming = pill
        pill.dimming = true
        if (!d.on) known[d.id] = d.copy(on = true, brightness = d.brightness.coerceAtLeast(STEP))
        apply()
    }

    override fun step(pill: HomePill, direction: Int) {
        val d = known[pill.entityId] ?: return
        val level = ((d.brightness + direction * STEP) / STEP * STEP).coerceIn(STEP, 100)
        known[d.id] = d.copy(on = true, brightness = level)
        bump(d.id)
        apply()
        removeCallbacks(sendBrightness)
        postDelayed(sendBrightness, BRIGHTNESS_SETTLE_MS)
    }

    /** Leaves dimming, sending the last level at once. */
    fun stopDimming() {
        val pill = dimming ?: return
        dimming = null
        pill.dimming = false
        removeCallbacks(sendBrightness)
        sendBrightness(pill.entityId)
        apply()
    }

    /** Back ends dimming (OK does too, through [press]); true when the key was used for that. */
    fun consumesKey(event: KeyEvent): Boolean {
        if (dimming == null || event.keyCode != KeyEvent.KEYCODE_BACK) return false
        if (event.action == KeyEvent.ACTION_UP) stopDimming()
        return true
    }

    private fun sendBrightness(id: String) {
        val wanted = known[id] ?: return
        val version = bump(id)
        val base = url
        worker.execute {
            val ok = hass.brightness(base, id, wanted.brightness)
            val after = hass.state(base, id)
            post { settle(id, version, ok, null, after) }
        }
    }

    /** A command's answer; ignored when a later press has already moved the device on. */
    private fun settle(id: String, version: Int, ok: Boolean, before: HassDevice?, after: HassDevice?) {
        if (versions[id] != version) return
        when {
            after != null && ok -> known[id] = after
            after != null -> {
                known[id] = after
                failed = id
            }
            !ok -> {
                before?.let { known[id] = it }
                failed = id
            }
        }
        apply()
    }

    private fun bump(id: String): Int = ((versions[id] ?: 0) + 1).also { versions[id] = it }

    private fun rebuild() {
        lights.removeAllViews()
        plugs.removeAllViews()
        lastFocused = null
        ids.forEach { id ->
            val pill = HomePill(context, id, this).apply {
                setOnFocusChangeListener { v, has ->
                    if (has) {
                        lastFocused = v as HomePill
                        refresh()
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
            val d = known[pill.entityId]
            pill.label = names[pill.entityId]?.takeIf { it.isNotBlank() }
                ?: HassJson.shortName(d?.name ?: pill.entityId.substringAfter('.'))
            pill.device = d
            pill.reachable = reachable
        }
        showReadout()
    }

    private fun showReadout() {
        val focused = pills().firstOrNull { it.isFocused }
        readout.text = if (focused != null) describe(focused) else summary()
    }

    private fun describe(pill: HomePill): CharSequence {
        val d = known[pill.entityId] ?: return ""
        return when {
            !reachable -> warn(context.getString(R.string.home_unreachable))
            failed == d.id -> warn(context.getString(R.string.home_failed))
            !d.available -> warn(context.getString(R.string.home_state_offline))
            dimming === pill -> gold(context.getString(R.string.home_dimming, d.brightness))
            d.on && d.dimmable -> context.getString(R.string.home_brightness_hint, d.brightness)
            d.on -> context.getString(R.string.home_state_on)
            else -> context.getString(R.string.home_state_off)
        }
    }

    private fun summary(): CharSequence {
        if (known.isEmpty() && reachable) return ""
        if (!reachable) return warn(context.getString(R.string.home_unreachable))
        val shown = ids.mapNotNull { known[it] }
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

    private fun gold(text: String) = colored(text, GOLD)

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
        const val STEP = 10
        /** Reads closer together than this are one read: focus moving pill to pill doesn't ask again. */
        const val MIN_READ_MS = 1500L
        /** Brightness goes out once the presses rest, not at every step. */
        const val BRIGHTNESS_SETTLE_MS = 350L
        const val GOLD = 0xFFFFD488.toInt()
    }
}
