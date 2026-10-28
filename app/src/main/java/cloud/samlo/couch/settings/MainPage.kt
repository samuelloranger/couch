package cloud.samlo.couch.settings

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import cloud.samlo.couch.R
import cloud.samlo.couch.data.Accents
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.Fetcher
import cloud.samlo.couch.godmode.GodmodePage
import cloud.samlo.couch.godmode.PRESSES
import cloud.samlo.couch.godmode.Unlock
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.update.Installer
import cloud.samlo.couch.update.UpdateCheck
import cloud.samlo.couch.update.Versions

/**
 * "Customize": every option on one TV-friendly list. Left/right changes a value in place,
 * OK cycles or opens it, and the panel on the right explains the focused option. Options that do
 * nothing in the chosen layout are left out, and the list rebuilds when the layout changes.
 */
class MainPage(private val ctx: SettingsContext) : SettingsPage {
    private val o = ctx.options
    private val config get() = ctx.config
    private val rows get() = config.layout != Config.LAYOUT_INDEX

    override fun build() {
        // Something missing goes first, where it's seen; once all is granted it moves out of the way.
        val missing = !Setup.allOn(ctx.activity)
        if (missing) setup()
        look()
        wallpaper()
        home()
        clock()
        standby()
        if (!missing) setup()
        system()
    }

    /** Back from Android's settings: show what was just granted. */
    override fun onResume() = o.rows().forEach { it.refresh() }

    private fun setup() {
        o.header(ctx.str(R.string.group_setup))
        for (item in Setup.items) {
            o.link(ctx.str(item.title), ctx.str(item.help), { ctx.str(if (item.isOn(ctx.activity)) R.string.yes else R.string.setup_turn_on) }) {
                item.open(ctx.activity)
            }
        }
    }

    private fun look() {
        o.header(ctx.str(R.string.group_look))
        o.choice(
            ctx.str(R.string.layout), ctx.str(R.string.layout_help),
            listOf(
                Option(ctx.str(R.string.layout_rows), Config.LAYOUT_ROWS),
                Option(ctx.str(R.string.layout_index), Config.LAYOUT_INDEX),
            ),
            { config.layout },
            {
                config.layout = it
                rebuild()
            },
        )
        if (rows) o.choice(
            ctx.str(R.string.card_size), ctx.str(R.string.card_size_help),
            listOf(
                Option(ctx.str(R.string.size_small), "small"),
                Option(ctx.str(R.string.size_medium), "medium"),
                Option(ctx.str(R.string.size_large), "large"),
            ),
            { config.cardSize }, { config.cardSize = it },
        )
        o.choice(
            ctx.str(R.string.accent), ctx.str(R.string.accent_help),
            Accents.all.map { Option(ctx.str(it.label), it.hex) },
            { config.accent },
            {
                config.accent = it
                Tokens.applyAccent(it)
                // The focus ring here follows the new colour right away.
                o.restyle()
            },
        )
        if (!rows) return
        o.toggle(ctx.str(R.string.show_labels), ctx.str(R.string.show_labels_help), { config.showLabels }, { config.showLabels = it })
        o.toggle(ctx.str(R.string.show_descriptions), ctx.str(R.string.show_descriptions_help), { config.showDescriptions }, { config.showDescriptions = it })
    }

    private fun wallpaper() {
        o.header(ctx.str(R.string.group_wallpaper))
        o.choice(
            ctx.str(R.string.wallpaper_source), ctx.str(R.string.wallpaper_source_help),
            listOf(
                Option(ctx.str(R.string.wallpaper_daily), "daily"),
                Option(ctx.str(R.string.wallpaper_reddit), "reddit"),
                Option(ctx.str(R.string.wallpaper_gradient), "gradient"),
                Option(ctx.str(R.string.wallpaper_custom), "custom"),
            ),
            { config.wallpaper }, { config.wallpaper = it },
        )
        o.text(
            ctx.str(R.string.subreddit), ctx.str(R.string.subreddit_help), Config.DEFAULT_SUBREDDIT,
            { "r/" + config.subreddit }, { config.subreddit = it.removePrefix("r/").ifEmpty { Config.DEFAULT_SUBREDDIT } },
        )
        o.choice(
            ctx.str(R.string.wallpaper_hours), ctx.str(R.string.wallpaper_hours_help),
            listOf(
                Option(ctx.str(R.string.hours_1), "1"),
                Option(ctx.str(R.string.hours_3), "3"),
                Option(ctx.str(R.string.hours_6), "6"),
                Option(ctx.str(R.string.hours_24), "24"),
            ),
            { config.wallpaperHours.toString() }, { config.wallpaperHours = it.toInt() },
        )
        // Index has no hero to darken; its wallpaper only shows in ambient mode.
        if (rows) o.choice(
            ctx.str(R.string.dim), ctx.str(R.string.dim_help),
            listOf(
                Option(ctx.str(R.string.dim_light), "light"),
                Option(ctx.str(R.string.dim_medium), "medium"),
                Option(ctx.str(R.string.dim_strong), "strong"),
            ),
            { config.dim }, { config.dim = it },
        )
    }

    private fun home() {
        o.header(ctx.str(R.string.group_home))
        if (rows) o.link(ctx.str(R.string.settings_rows), ctx.str(R.string.settings_rows_help)) { open(SettingsActivity.PAGE_ROWS) }
        o.link(ctx.str(R.string.settings_inputs), ctx.str(R.string.settings_inputs_help)) { open(SettingsActivity.PAGE_INPUTS) }
        o.link(ctx.str(R.string.settings_channels), ctx.str(R.string.settings_channels_help)) { open(SettingsActivity.PAGE_CHANNELS) }
        o.link(ctx.str(R.string.settings_visibility), ctx.str(R.string.settings_visibility_help)) {
            Dialogs.visibility(ctx.activity, config, ctx.files.dir) {}
        }
        if (rows) o.link(ctx.str(R.string.settings_organize), ctx.str(R.string.settings_organize_help)) {
            ctx.activity.setResult(SettingsActivity.RESULT_ORGANIZE)
            ctx.activity.finish()
        }
    }

    private fun clock() {
        o.header(ctx.str(R.string.group_clock))
        o.toggle(ctx.str(R.string.clock24h), ctx.str(R.string.clock24h_help), { config.clock24h }, { config.clock24h = it })
        if (rows) o.toggle(ctx.str(R.string.show_date), ctx.str(R.string.show_date_help), { config.showDate }, { config.showDate = it })
        o.toggle(ctx.str(R.string.weather), ctx.str(R.string.weather_help), { config.weather }, { config.weather = it })
        val auto = ctx.str(R.string.city_auto)
        o.text(
            ctx.str(R.string.city), ctx.str(R.string.city_help), auto,
            { config.weatherCity.ifEmpty { auto } }, { config.weatherCity = it },
        )
        o.choice(
            ctx.str(R.string.units), "", listOf(Option("°C", "c"), Option("°F", "f")),
            { if (config.fahrenheit) "f" else "c" }, { config.fahrenheit = it == "f" },
        )
    }

    private fun standby() {
        o.header(ctx.str(R.string.group_standby))
        val minutes = listOf(2, 5, 10, 20).map { Option(ctx.activity.getString(R.string.minutes_short, it), it.toString()) }
        o.choice(
            ctx.str(R.string.ambient), ctx.str(R.string.ambient_help),
            listOf(Option(ctx.str(R.string.ambient_never), "0")) + minutes,
            { config.ambientMinutes.toString() }, { config.ambientMinutes = it.toInt() },
        )
    }

    private fun system() {
        o.header(ctx.str(R.string.group_system))
        o.link(ctx.str(R.string.android_settings), ctx.str(R.string.android_settings_help)) {
            ctx.activity.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        o.link(ctx.str(R.string.settings_tvs), ctx.str(R.string.settings_tvs_help), { ctx.str(if (config.syncEnabled) R.string.yes else R.string.no) }) {
            open(SettingsActivity.PAGE_TVS)
        }
        o.link(ctx.str(R.string.remote_title), ctx.str(R.string.remote_link_help), { ctx.str(if (config.remoteButtons) R.string.yes else R.string.no) }) {
            open(RemotePage.PAGE)
        }
        o.toggle(ctx.str(R.string.check_updates), ctx.str(R.string.check_updates_help), { config.checkUpdates }, { config.checkUpdates = it })
        about()
        if (config.godmode) godmodeRow()
    }

    /** The hidden page, once seven OKs on About found it. */
    private fun godmodeRow() {
        o.link(ctx.str(R.string.godmode), ctx.str(R.string.godmode_help), { "›" }) { open(GodmodePage.page) }
    }

    /** The installed version; OK installs a newer one when the last daily check found it. */
    private fun about() {
        val fetcher = Fetcher(ctx.activity)
        val update = UpdateCheck(ctx.activity, fetcher).pending(config)
        val help = update?.let { ctx.activity.getString(R.string.about_update, it.version) } ?: ctx.str(R.string.about_help)
        o.link(ctx.str(R.string.about), help, { Versions.installed(ctx.activity) }) {
            if (update != null && !Installer.running) {
                Toast.makeText(ctx.activity, R.string.update_downloading, Toast.LENGTH_SHORT).show()
                Installer(ctx.activity, fetcher).install(update) { message ->
                    message?.let { Toast.makeText(ctx.activity, it, Toast.LENGTH_LONG).show() }
                }
                return@link
            }
            // The hidden second route in: seven OKs agree on a version that is current.
            when (val result = if (config.godmode) Unlock.Outcome.Nothing else unlock.ok(update != null)) {
                is Unlock.Outcome.Progress -> {
                    // One toast at a time: queued toasts would lag behind fast presses.
                    countdown?.cancel()
                    countdown = Toast.makeText(
                        ctx.activity,
                        ctx.activity.resources.getQuantityString(R.plurals.unlock_countdown, result.pressesLeft, result.pressesLeft) + " " +
                            "●".repeat(result.dots) + "○".repeat(PRESSES - result.dots),
                        Toast.LENGTH_SHORT,
                    ).apply { show() }
                }
                is Unlock.Outcome.Unlocked -> {
                    countdown?.cancel()
                    config.godmode = true
                    config.save(ctx.files.dir)
                    open(GodmodePage.page)
                }
                else -> {}
            }
        }
    }

    private val unlock = Unlock()
    private var countdown: Toast? = null

    private fun open(page: String) = ctx.activity.startActivity(SettingsActivity.intent(ctx.activity, page))

    /** After a layout change: the same list for the other layout, focus back on the Layout row. */
    private fun rebuild() = ctx.layout.list.post {
        ctx.layout.list.removeAllViews()
        build()
        o.rows().firstOrNull { it.label.text == ctx.str(R.string.layout) }?.requestFocus()
    }
}
