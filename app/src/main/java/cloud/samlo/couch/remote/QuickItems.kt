package cloud.samlo.couch.remote

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import cloud.samlo.couch.R
import cloud.samlo.couch.godmode.Sleep

/**
 * What the Settings button lists: the settings Couch can open or change itself, the TV's picture
 * and sound settings ([tvItems], on a paired Bravia), then the TV's own screens. A row only appears when this TV has what it opens, so a TV without Sony's screens
 * simply shows fewer. [tvMenu] opens the TV's own quick settings, for what only the TV can change
 * (picture mode, brightness); it's null when no Settings button is set up to replay.
 */
object QuickItems {
    private val SLEEP_STEPS = listOf(0, 15, 30, 45, 60, 90, 120)

    fun build(context: Context, tvMenu: (() -> Unit)?, tvItems: List<PanelItem> = emptyList()): List<PanelItem> {
        val settings = context.getString(R.string.quick_section_settings)
        val tv = context.getString(R.string.quick_section_tv)
        val items = mutableListOf<PanelItem>()
        fun open(key: String, section: String, label: Int, glyph: Int, intent: Intent) {
            if (!context.opens(intent)) return
            items += PanelItem(key, section, context.getString(label), PanelItem.Icon.Glyph(glyph)) { it.launch(intent) }
        }
        open("settings", settings, R.string.quick_settings, R.drawable.ic_settings, Intent(Settings.ACTION_SETTINGS))
        open("network", settings, R.string.quick_network, R.drawable.qs_network, Intent(Settings.ACTION_WIFI_SETTINGS))
        open("bluetooth", settings, R.string.quick_bluetooth, R.drawable.qs_bluetooth, slice("content://com.android.tv.settings.accessories.sliceprovider/general"))
        open("accessibility", settings, R.string.quick_accessibility, R.drawable.qs_accessibility, Intent("android.settings.ACCESSIBILITY_TV_OEM_LINK"))
        open("parental", settings, R.string.quick_parental, R.drawable.qs_lock, slice("content://com.sony.dtv.parentalcontrolsettings.slice/device/parental_controls"))
        items += PanelItem(
            "sleep", settings, context.getString(R.string.quick_sleep), PanelItem.Icon.Glyph(R.drawable.qs_sleep),
            detail = { sleepLabel(context, Sleep.minutes(context)) },
            adjust = { c, by -> Sleep.set(c, nextSleep(Sleep.minutes(c), by)) },
        )
        items += tvItems
        if (tvMenu != null) {
            items += PanelItem("tv-menu", tv, context.getString(R.string.quick_tv_menu), PanelItem.Icon.Glyph(R.drawable.qs_tune), detail = {
                context.getString(R.string.quick_tv_menu_detail)
            }) { tvMenu() }
        }
        open("picture", tv, R.string.quick_picture, R.drawable.qs_picture, Intent("com.sony.dtv.base.picturesettings"))
        open("sound", tv, R.string.quick_sound, R.drawable.qs_sound, Intent("com.sony.dtv.base.soundsettings"))
        open("notifications", tv, R.string.quick_notifications, R.drawable.qs_bell, Intent("com.sony.dtv.intent.action.SHOW_NOTIFICATIONCENTER").putExtra("trigger", "QuickSettings"))
        open("eco", tv, R.string.quick_eco, R.drawable.qs_eco, Intent().setClassName("com.sony.dtv.ecodashboard", "com.sony.dtv.ecodashboard.presentation.activity.EcoDashboardActivity"))
        open("timers", tv, R.string.quick_timers, R.drawable.qs_timer, timers().putExtra("launch_ontimer", true))
        open("alarm", tv, R.string.quick_alarm, R.drawable.qs_alarm, timers().putExtra("launch_point", "alarm"))
        open("theatre", tv, R.string.quick_theatre, R.drawable.ic_speaker, Intent().setClassName("com.sony.dtv.HomeTheatreControl", "com.sony.dtv.HomeTheatreControl.HomeTheatreControlActivity"))
        open("power-on", tv, R.string.quick_power_on, R.drawable.qs_power, slice("content://com.google.android.apps.tv.launcherx.sliceprovider/power_boot_resume"))
        return items
    }

    /** The sleep timer step after [minutes] ([by] 1) or before it (-1), wrapping around off. */
    fun nextSleep(minutes: Int, by: Int = 1): Int {
        val at = SLEEP_STEPS.indexOfLast { it <= minutes }.coerceAtLeast(0)
        return SLEEP_STEPS[(at + by + SLEEP_STEPS.size) % SLEEP_STEPS.size]
    }

    private fun sleepLabel(context: Context, minutes: Int) =
        if (minutes == 0) context.getString(R.string.no) else context.getString(R.string.quick_sleep_minutes, minutes)

    private fun slice(uri: String) = Intent("android.settings.SLICE_SETTINGS").putExtra("slice_uri", uri)

    private fun timers() = Intent().setClassName("com.sony.dtv.timers", "com.sony.dtv.activity.TimersMainActivity")

    private fun Context.opens(intent: Intent): Boolean {
        val found = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return false
        // A slice screen exists everywhere; the slice it shows only exists where its app does.
        val slice = intent.getStringExtra("slice_uri") ?: return found.activityInfo.exported
        val authority = slice.removePrefix("content://").substringBefore('/')
        return packageManager.resolveContentProvider(authority, 0) != null
    }

    private fun Context.launch(intent: Intent) {
        try {
            startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            // Gone since the list was built (an app was disabled): nothing to open.
        } catch (e: SecurityException) {
            // Not exported to other apps after all.
        }
    }
}
