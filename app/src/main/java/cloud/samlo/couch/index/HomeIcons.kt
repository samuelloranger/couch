package cloud.samlo.couch.index

import cloud.samlo.couch.R
import cloud.samlo.couch.hass.HassDevice

/** The glyph for a Home Assistant device: a bulb, a plug, a washer or a dishwasher; filled when [lit]. */
object HomeIcons {
    fun of(id: String, lit: Boolean): Int = when {
        id.startsWith(HassDevice.LIGHT) -> if (lit) R.drawable.ic_home_bulb_lit else R.drawable.ic_home_bulb
        id.startsWith(HassDevice.SWITCH) -> if (lit) R.drawable.ic_home_plug_lit else R.drawable.ic_home_plug
        "dish" in id || "vaisselle" in id -> if (lit) R.drawable.ic_home_dishwasher_lit else R.drawable.ic_home_dishwasher
        else -> if (lit) R.drawable.ic_home_washer_lit else R.drawable.ic_home_washer
    }
}
