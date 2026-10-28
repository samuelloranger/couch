package cloud.samlo.couch.settings

import cloud.samlo.couch.R
import cloud.samlo.couch.data.Accents
import cloud.samlo.couch.data.AppSource
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.Fetcher
import cloud.samlo.couch.update.UpdateCheck
import cloud.samlo.couch.update.Versions

/**
 * The groups of Customize, listed in the rail. Each says in one line how it is set, so the rail
 * reads as a summary of the whole page.
 */
enum class Group(val title: Int, val icon: Int) {
    LOOK(R.string.group_look, R.drawable.ic_group_look),
    WALLPAPER(R.string.group_wallpaper, R.drawable.ic_group_wallpaper),
    HOME(R.string.group_home, R.drawable.ic_group_home),
    CLOCK(R.string.group_clock, R.drawable.ic_group_clock),
    COUCH(R.string.group_system, R.drawable.ic_group_couch),
    ;

    fun summary(ctx: SettingsContext): String {
        val c = ctx.activity
        val config = ctx.config
        return when (this) {
            LOOK -> listOf(
                ctx.str(if (config.layout == Config.LAYOUT_INDEX) R.string.layout_index else R.string.layout_rows),
                ctx.str((Accents.all.firstOrNull { it.hex.equals(config.accent, ignoreCase = true) } ?: Accents.all.first()).label),
            )
            WALLPAPER -> listOf(
                ctx.str(SOURCES[config.wallpaper] ?: R.string.wallpaper_daily),
                if (config.ambientMinutes == 0) ctx.str(R.string.summary_no_ambient) else c.getString(R.string.summary_ambient, config.ambientMinutes),
            )
            HOME -> {
                val hidden = AppSource.launchable(c.packageManager).count { it.activityInfo.packageName in config.hiddenApps }
                listOf(if (hidden == 0) ctx.str(R.string.summary_no_hidden) else c.resources.getQuantityString(R.plurals.summary_hidden, hidden, hidden))
            }
            CLOCK -> listOf(
                ctx.str(if (config.clock24h) R.string.summary_clock_24 else R.string.summary_clock_12),
                ctx.str(if (config.weather) R.string.summary_weather_on else R.string.summary_weather_off),
            )
            COUCH -> {
                val update = UpdateCheck(c, Fetcher(c)).pending(config)
                listOf(update?.let { c.getString(R.string.summary_update, it.version) } ?: c.getString(R.string.summary_version, Versions.installed(c)))
            }
        }.joinToString(", ")
    }

    companion object {
        private val SOURCES = mapOf(
            "daily" to R.string.wallpaper_daily,
            "reddit" to R.string.wallpaper_reddit,
            "gradient" to R.string.wallpaper_gradient,
            "custom" to R.string.wallpaper_custom,
        )

        /** The group a page of settings belongs to; null for the pages that stand on their own. */
        fun of(page: String?): Group? = when (page) {
            SettingsActivity.PAGE_ROWS, SettingsActivity.PAGE_APPS, SettingsActivity.PAGE_INPUTS -> HOME
            SettingsActivity.PAGE_ACCESS, SettingsActivity.PAGE_TVS, RemotePage.PAGE -> COUCH
            else -> null
        }
    }
}
