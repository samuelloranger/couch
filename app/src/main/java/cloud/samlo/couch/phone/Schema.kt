package cloud.samlo.couch.phone

import android.content.Context
import cloud.samlo.couch.R
import cloud.samlo.couch.data.Accents
import cloud.samlo.couch.phone.SchemaModel.Group
import cloud.samlo.couch.phone.SchemaModel.Option
import cloud.samlo.couch.phone.SchemaModel.Row
import cloud.samlo.couch.phone.SchemaModel.When
import org.json.JSONObject

/**
 * Customize's simple settings, described for the phone from the same strings and options the
 * pages use, in the TV's language. Left out: Godmode, remote-button learning, Home Assistant
 * devices, channels and the apps and inputs arrangement (the phone has screens for those).
 */
object Schema {
    fun build(c: Context): JSONObject {
        fun s(id: Int) = c.getString(id)

        val look = Group(
            "look", s(R.string.group_look),
            listOf(
                Row("accent", s(R.string.accent), s(R.string.accent_help), "swatches", Accents.all.map { Option(it.hex, s(it.label), it.hex) }),
                Row(
                    "ambientMinutes", s(R.string.ambient), s(R.string.ambient_help), "choice",
                    listOf(Option(0, s(R.string.ambient_never))) + listOf(2, 5, 10, 20).map { Option(it, c.getString(R.string.minutes_short, it)) },
                ),
                Row(
                    "wallpaper", s(R.string.wallpaper_source), s(R.string.wallpaper_source_help), "choice",
                    listOf(
                        Option("daily", s(R.string.wallpaper_daily)), Option("reddit", s(R.string.wallpaper_reddit)),
                        Option("gradient", s(R.string.wallpaper_gradient)), Option("custom", s(R.string.wallpaper_custom)),
                    ),
                ),
                Row("subreddit", s(R.string.subreddit), s(R.string.subreddit_help), "text", visibleWhen = When("wallpaper", "reddit")),
                Row(
                    "wallpaperHours", s(R.string.wallpaper_hours), s(R.string.wallpaper_hours_help), "choice",
                    listOf(1 to R.string.hours_1, 3 to R.string.hours_3, 6 to R.string.hours_6, 24 to R.string.hours_24).map { (h, label) -> Option(h, s(label)) },
                    When("wallpaper", "reddit"),
                ),
            ),
        )
        val home = Group(
            "home", s(R.string.group_home),
            listOf(
                Row(
                    "appSort", s(R.string.app_sort), s(R.string.app_sort_help), "choice",
                    listOf(Option("usage", s(R.string.sort_usage)), Option("manual", s(R.string.sort_manual)), Option("alphabetical", s(R.string.sort_alphabetical))),
                ),
            ),
        )
        val clock = Group(
            "clock", s(R.string.group_clock),
            listOf(
                Row("clock24h", s(R.string.clock24h), s(R.string.clock24h_help), "toggle"),
                Row("weather", s(R.string.weather), s(R.string.weather_help), "toggle"),
                Row("weatherCity", s(R.string.city), s(R.string.city_help), "text", visibleWhen = When("weather", true)),
                Row("fahrenheit", s(R.string.units), s(R.string.units_help), "choice", listOf(Option(false, "°C"), Option(true, "°F")), When("weather", true)),
            ),
        )
        val couch = Group(
            "couch", s(R.string.group_system),
            listOf(
                Row("checkUpdates", s(R.string.check_updates), s(R.string.check_updates_help), "toggle"),
                Row("syncEnabled", s(R.string.tvs_share), s(R.string.tvs_share_help), "toggle"),
                Row(
                    "syncAllTvs", s(R.string.tvs_scope), s(R.string.tvs_scope_help), "choice",
                    listOf(Option(false, s(R.string.tvs_scope_this)), Option(true, s(R.string.tvs_scope_all))),
                    When("syncEnabled", true),
                ),
            ),
        )
        return SchemaModel.toJson(listOf(look, home, clock, couch))
    }
}
