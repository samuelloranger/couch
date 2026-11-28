package cloud.samlo.couch.settings

import cloud.samlo.couch.ui.Fonts
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import cloud.samlo.couch.R
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.Prefs
import cloud.samlo.couch.hass.HassDevice
import cloud.samlo.couch.hass.HassDiscovery
import cloud.samlo.couch.hass.HassJson
import cloud.samlo.couch.hass.HomeAssistant
import cloud.samlo.couch.hass.PhoneLink
import cloud.samlo.couch.hass.QrCode
import cloud.samlo.couch.index.HomeStatus
import cloud.samlo.couch.ui.Popover
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.Type
import cloud.samlo.couch.ui.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home Assistant: connect from the phone, then pick up to six lights, plugs and appliances for the
 * Index's row and give them short names. The row's order is the order they were picked in.
 */
class HomeAssistantPage(private val ctx: SettingsContext) : SettingsPage {
    private val o = ctx.options
    private val config get() = ctx.config
    private val hass = HomeAssistant(Prefs.of(ctx.activity))
    /** Cancelled with the page: nothing lands on a closed screen. */
    private val scope = MainScope()
    private val worker = Dispatchers.IO.limitedParallelism(1)
    /** Every light and plug, once read; null while reading or after a failed read. */
    private var devices: List<HassDevice>? = null
    private var reading = false
    private var unreachable = false
    private var link: PhoneLink? = null
    private var linkPopover: Popover? = null

    private val url get() = HassJson.baseUrl(config.homeAssistantUrl)

    override fun build() {
        ctx.layout.title.text = ctx.str(R.string.hass_title)
        ctx.layout.hint.text = ctx.str(R.string.hass_hint)
        val connected = hass.connected(url)
        o.header(ctx.str(R.string.hass_connection))
        lateinit var connect: OptionRow
        connect = o.link(ctx.str(R.string.hass_connect), ctx.str(R.string.hass_connect_help), { if (connected) url else ctx.str(R.string.hass_not_connected) }) {
            openLink(connect)
        }
        if (!connected) return
        o.link(ctx.str(R.string.hass_forget), ctx.str(R.string.hass_forget_help)) { forget() }

        val all = devices
        if (all == null) {
            o.note(ctx.str(if (unreachable) R.string.hass_unreachable else R.string.hass_loading))
            if (!reading && !unreachable) read()
            return
        }
        val byId = all.associateBy { it.id }
        val chosen = config.homeDevices
        o.header(ctx.activity.getString(R.string.hass_in_row, chosen.size, Config.MAX_HOME_DEVICES))
        if (chosen.isEmpty()) o.note(ctx.str(R.string.hass_in_row_none))
        chosen.forEach { id -> deviceRow(id, byId[id]) }
        if (chosen.isNotEmpty()) {
            o.header(ctx.str(R.string.hass_names))
            chosen.forEach { id ->
                val fallback = HassJson.shortName(byId[id]?.name ?: id.substringAfter('.'))
                o.text(byId[id]?.name ?: id, ctx.str(R.string.hass_name_help), fallback, { config.homeNames[id] ?: fallback }) { name ->
                    config.homeNames = if (name.isEmpty()) config.homeNames - id else config.homeNames + (id to HassJson.shortName(name))
                }
            }
        }
        val others = all.filter { it.id !in chosen }
        others.filter { !it.isAppliance }.takeIf { it.isNotEmpty() }?.let { list ->
            o.header(ctx.str(R.string.hass_others))
            list.forEach { deviceRow(it.id, it) }
        }
        others.filter { it.isAppliance }.takeIf { it.isNotEmpty() }?.let { list ->
            o.header(ctx.str(R.string.hass_appliances))
            list.forEach { deviceRow(it.id, it) }
        }
    }

    private fun deviceRow(id: String, d: HassDevice?) {
        val state = if (d == null) ctx.str(R.string.home_state_offline) else HomeStatus.text(ctx.activity, d)
        val title = d?.name ?: id
        o.toggle(title, "$id · $state", { id in config.homeDevices }) { on ->
            if (on && config.homeDevices.size >= Config.MAX_HOME_DEVICES) {
                Toast.makeText(ctx.activity, R.string.hass_full, Toast.LENGTH_LONG).show()
                return@toggle
            }
            config.homeDevices = if (on) config.homeDevices + id else config.homeDevices - id
            if (!on) config.homeNames = config.homeNames - id
            // The row moves between sections: build again once the toggle has saved.
            ctx.layout.list.post { rebuild(title) }
        }
    }

    private fun read() {
        reading = true
        val base = url
        scope.launch {
            val all = withContext(worker) { hass.all(base)?.also { hass.remember(it) } }
            reading = false
            devices = all
            unreachable = all == null
            rebuild(null)
        }
    }

    private var linkPending = false

    private fun openLink(anchor: OptionRow) {
        if (linkPending) return
        linkPending = true
        link?.stop()
        val activity = ctx.activity
        val base = url
        scope.launch {
            val suggested = base.ifEmpty { withContext(worker) { HassDiscovery.find(activity, DISCOVERY_MS) } ?: "" }
            val phone = PhoneLink(activity, hass, suggested) { linked -> scope.launch { linked(linked) } }
            val address = try {
                withContext(worker) { phone.start() }
            } catch (e: CancellationException) {
                // The page closed while it started: nobody will stop it later.
                phone.stop()
                throw e
            }
            linkPending = false
            if (address == null) {
                Toast.makeText(activity, R.string.hass_link_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            link = phone
            showLink(address, anchor)
        }
    }

    /** The QR code beside [anchor], over the row it came from: Back closes it, and so does the phone. */
    private fun showLink(address: String, anchor: OptionRow) {
        val activity = ctx.activity
        val qr = ImageView(activity).apply {
            setImageBitmap(QrCode.encode(address).bitmap(QR_SCALE))
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        fun line(text: String, size: Float, color: Int) = TextView(activity).apply {
            this.text = text
            textSize = size
            typeface = Fonts.hanken(activity, 500)
            setTextColor(color)
            gravity = Gravity.CENTER
        }
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = activity.dp(BODY_PAD)
            setPadding(pad, pad, pad, pad)
            addView(line(ctx.str(R.string.hass_link_steps), Type.LABEL, Tokens.textMuted))
            addView(qr, LinearLayout.LayoutParams(activity.dp(QR_DP), activity.dp(QR_DP)).apply {
                topMargin = activity.dp(12)
                bottomMargin = activity.dp(12)
            })
            addView(line(address, Type.LABEL, Tokens.textPrimary))
            addView(line(ctx.str(R.string.hass_link_wait), Type.CAPTION, Tokens.textMuted).apply { setPadding(0, activity.dp(10), 0, 0) })
        }
        linkPopover = Popover(activity, anchor, body, QR_DP + 2 * BODY_PAD + 2 * PANEL_PAD).apply {
            setOnDismissListener { stopLink() }
            show()
        }
    }

    private fun linked(newUrl: String) {
        config.homeAssistantUrl = newUrl
        config.save(ctx.files.dir)
        devices = null
        unreachable = false
        linkPopover?.dismiss()
        Toast.makeText(ctx.activity, R.string.hass_linked, Toast.LENGTH_SHORT).show()
        rebuild(null)
    }

    private fun stopLink() {
        link?.stop()
        link = null
        linkPopover = null
    }

    private fun forget() {
        hass.token = ""
        config.homeAssistantUrl = ""
        config.homeDevices = emptyList()
        config.homeNames = emptyMap()
        config.save(ctx.files.dir)
        devices = null
        unreachable = false
        rebuild(null)
    }

    private fun rebuild(focus: String?) {
        ctx.layout.list.removeAllViews()
        build()
        ctx.layout.list.post {
            (o.rows().firstOrNull { focus != null && it.label.text == focus } ?: o.rows().firstOrNull())?.requestFocus()
        }
    }

    /** Back on the page (after Home Assistant came back, say): read again if the last read failed. */
    override fun onResume() {
        if (unreachable) {
            unreachable = false
            rebuild(null)
        }
    }

    override fun onDestroy() {
        linkPopover?.dismiss()
        stopLink()
        scope.cancel()
    }

    private companion object {
        const val DISCOVERY_MS = 2500L
        const val QR_SCALE = 8
        const val QR_DP = 180
        /** The link panel's own padding, and the body's inside it. */
        const val PANEL_PAD = 4
        const val BODY_PAD = 10
    }
}
