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
import cloud.samlo.couch.index.HomeStatus
import cloud.samlo.couch.index.IndexBuilder
import cloud.samlo.couch.ui.Tokens

/**
 * What the Input button lists: the Home Assistant row's devices, the TV's inputs, then the apps in
 * the Index list's order.
 */
object InputItems {
    /** The items, and the Home Assistant devices among them for [home] to follow. */
    class Loaded(val items: List<PanelItem>, val url: String, val ids: List<String>)

    /**
     * Reads the apps, inputs and devices; slow, so call it from a worker. Nothing here waits on
     * Home Assistant: devices are named from what it last said, and [home] reads their states once
     * the panel opens.
     */
    fun load(context: Context, home: HassDevices): Loaded {
        val files = UserFiles(context)
        val config = Config.load(files.dir)
        Tokens.applyAccent(config.accent)
        val data = IndexBuilder.build(context, config, files.icons, withChannels = false)
        val inputs = context.getString(R.string.section_inputs)
        val apps = context.getString(R.string.section_apps)

        val hass = HomeAssistant(Prefs.of(context))
        val url = HassJson.baseUrl(config.homeAssistantUrl)
        // Lights, then plugs, then appliances, as in the Index's row.
        val ids = if (hass.connected(url)) config.homeDevices.filter { HassDevice.supported(it) }.sortedBy { HassDevice.order(it) } else emptyList()
        val section = context.getString(R.string.section_home)
        val devices = ids.map { id ->
            val label = config.homeNames[id]?.takeIf { it.isNotBlank() } ?: HassJson.shortName(hass.knownName(id) ?: id.substringAfter('.'))
            device(context, home, id, label, section)
        }
        return Loaded(
            devices + data.inputs.map { PanelItem.of(it, inputs, null) } + data.entries.map { PanelItem.of(it.app, apps, it.icon) },
            url, ids,
        )
    }

    /** OK switches a light or plug and the panel stays open; holding OK on a dimmable light sets its brightness. */
    private fun device(context: Context, home: HassDevices, id: String, label: String, section: String) = PanelItem(
        key = "hass:$id",
        section = section,
        label = label,
        icon = PanelItem.Icon.Home(id, { home[id] }, { home.reachable }),
        keepOpen = true,
        detail = { HomeStatus.text(context, home[id], home.reachable, home.failed == id, home.isDimming(id), home.finish(id)) },
        adjust = { _, by -> home.step(id, by) },
        hold = { home.canDim(id).also { if (it) home.startDimming(id) } },
        endHold = { home.stopDimming() },
        action = { home.toggle(id) },
    )
}
