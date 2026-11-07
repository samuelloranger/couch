package cloud.samlo.couch.remote

import android.content.Context
import cloud.samlo.couch.R
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.Prefs
import cloud.samlo.couch.data.UserFiles
import cloud.samlo.couch.hass.HassDevice
import cloud.samlo.couch.hass.HassDevices
import cloud.samlo.couch.hass.HassJson
import cloud.samlo.couch.hass.HomeAssistant
import cloud.samlo.couch.index.IndexBuilder
import cloud.samlo.couch.ui.Tokens

/**
 * What the Input button lists: the Home Assistant row's lights and plugs, the TV's inputs, then
 * the apps in the Index list's order.
 */
object InputItems {
    /** The items, and what Home Assistant said about the devices among them. */
    class Loaded(val items: List<PanelItem>, val url: String, val ids: List<String>, val states: List<HassDevice>)

    /** Reads the apps, inputs and devices; slow, so call it from a worker. [home] answers the devices' items. */
    fun load(context: Context, home: HassDevices): Loaded {
        val files = UserFiles(context)
        val config = Config.load(files.dir)
        Tokens.applyAccent(config.accent)
        val data = IndexBuilder.build(context, config, files.icons)
        val inputs = context.getString(R.string.section_inputs)
        val apps = context.getString(R.string.section_apps)

        val hass = HomeAssistant(Prefs.of(context))
        val url = HassJson.baseUrl(config.homeAssistantUrl)
        // Lights first, then plugs, as in the Index's row.
        val ids = if (hass.connected(url)) config.homeDevices.filter { HassDevice.supported(it) }.sortedBy { !it.startsWith(HassDevice.LIGHT) } else emptyList()
        val states = if (ids.isEmpty()) emptyList() else hass.all(url)?.filter { it.id in ids }.orEmpty()
        val names = states.associate { it.id to it.name }
        val section = context.getString(R.string.section_home)
        val devices = ids.map { id ->
            val label = config.homeNames[id]?.takeIf { it.isNotBlank() } ?: HassJson.shortName(names[id] ?: id.substringAfter('.'))
            device(context, home, id, label, section)
        }
        return Loaded(
            devices + data.inputs.map { PanelItem.of(it, inputs, null) } + data.entries.map { PanelItem.of(it.app, apps, it.icon) },
            url, ids, states,
        )
    }

    /** OK switches it and the panel stays open; holding OK on a dimmable light sets its brightness. */
    private fun device(context: Context, home: HassDevices, id: String, label: String, section: String) = PanelItem(
        key = "hass:$id",
        section = section,
        label = label,
        icon = PanelItem.Icon.Home(id.startsWith(HassDevice.LIGHT), { home[id] }, { home.reachable }),
        keepOpen = true,
        detail = { detail(context, home, id) },
        adjust = { _, by -> home.step(id, by) },
        hold = { home.canDim(id).also { if (it) home.startDimming(id) } },
        endHold = { home.stopDimming() },
        action = { home.toggle(id) },
    )

    private fun detail(context: Context, home: HassDevices, id: String): String {
        val d = home[id] ?: return ""
        return when {
            !home.reachable -> context.getString(R.string.home_state_unreachable)
            home.failed == id -> context.getString(R.string.home_failed_short)
            !d.available -> context.getString(R.string.home_state_offline)
            d.on && d.dimmable && d.brightness > 0 -> context.getString(R.string.home_percent, d.brightness)
            d.on -> context.getString(R.string.home_state_on)
            else -> context.getString(R.string.home_state_off)
        }
    }
}
