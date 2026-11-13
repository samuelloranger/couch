package cloud.samlo.couch.settings

import android.content.Intent
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import cloud.samlo.couch.R
import cloud.samlo.couch.data.Accents
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.Fetcher
import cloud.samlo.couch.godmode.GodmodePage
import cloud.samlo.couch.godmode.PRESSES
import cloud.samlo.couch.godmode.Unlock
import cloud.samlo.couch.sync.Sync
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.accentChip
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.plainChip
import cloud.samlo.couch.update.Installer
import cloud.samlo.couch.update.UpdateCheck
import cloud.samlo.couch.update.Versions
import kotlin.concurrent.thread

/**
 * "Customize": the groups down the left, the focused group's options on the right. Up and down
 * the rail switch groups, Right or OK steps into the options, and Back or Left (on a row that
 * doesn't use it) steps out again. Left/right changes a value in place, OK cycles or opens it, and
 * the focused row explains itself. Rows that only matter for another setting sit indented under
 * it and show only when they apply.
 */
class MainPage(private val ctx: SettingsContext) : SettingsPage {
    private val o = ctx.options
    private val config get() = ctx.config
    private val rail = Rail(ctx, focusable = true)
    private var current = Group.LOOK

    /** How many accesses were off at the last build, so a grant on return updates the warning. */
    private var off = 0

    override fun build() {
        off = offCount()
        rail.build(current, if (off > 0) missingWarning() else null)
        rail.listen(onFocus = { if (it != current) show(it) }, onEnter = ::enter)
        // A saved change shows at once in its group's line; the other lines can't have changed.
        o.onSave = { rail.refresh(current) }
        show(current)
    }

    override fun focusStart() = rail.item(current).requestFocus()

    /** Back from another screen: show what changed, and redo the warning once a grant changed it. */
    override fun onResume() {
        // The page just closed saved its own copy; saving this one as it was would undo that.
        ctx.config = Config.load(ctx.files.dir)
        if (off == offCount()) {
            o.rows().forEach { it.refresh() }
            if (current == Group.COUCH) showUpdate?.invoke()
            rail.refresh()
            return
        }
        val inPage = inPage()
        val focus = (ctx.activity.currentFocus as? OptionRow)?.label?.text?.toString()
        build()
        // Only the update buttons are focusable without being rows; Check now is always there.
        if (inPage && focus == null) checkNowButton?.requestFocus() else if (inPage) refocus(focus) else focusStart()
    }

    /** A Back press that started in the options; its repeats and release are swallowed too. */
    private var backToRail = false

    /**
     * Back from the options returns to their group in the rail, instead of leaving Customize.
     * The whole press is kept, so holding Back doesn't go on to close the page from the rail.
     */
    override fun consumesKey(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return false
        val first = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        if (first) backToRail = inPage()
        if (!backToRail) return false
        if (first) rail.item(current).requestFocus()
        if (event.action == KeyEvent.ACTION_UP) backToRail = false
        return true
    }

    private fun inPage() = ctx.layout.list.hasFocus()

    private fun enter() {
        o.rows().firstOrNull()?.requestFocus()
    }

    /** Fills the page on the right with [group]'s options. */
    private fun show(group: Group) {
        current = group
        rail.select(group)
        ctx.layout.list.removeAllViews()
        ctx.layout.title.text = ctx.str(group.title)
        // No hint here: the options start level with the first group in the rail.
        ctx.layout.hint.visibility = View.GONE
        ctx.layout.title.setPadding(ctx.activity.dp(20), 0, 0, ctx.activity.dp(20))
        when (group) {
            Group.LOOK -> look()
            Group.HOME -> home()
            Group.CLOCK -> clock()
            Group.COUCH -> system()
        }
        // Left from a row that doesn't use it goes back to this group, not to whichever is nearest.
        val back = rail.item(group).id
        val all = o.rows()
        all.forEach { it.nextFocusLeftId = back }
        all.firstOrNull()?.let { it.nextFocusUpId = it.id }
        all.lastOrNull()?.let { if (it.nextFocusDownId == View.NO_ID) it.nextFocusDownId = it.id }
    }

    private fun offCount() = Setup.items.count { !it.isOn(ctx.activity) }

    /** A line above the groups while an access is missing; it opens the permissions page. */
    private fun missingWarning(): RailItem {
        val missing = Setup.items.filterNot { it.isOn(ctx.activity) }
        return RailItem(ctx.activity, R.drawable.ic_group_warning, ctx.activity.resources.getQuantityString(R.plurals.setup_missing, missing.size, missing.size)).apply {
            summary.text = missing.joinToString(", ") { ctx.str(it.title) }
            isFocusable = true
            warn()
            setOnClickListener { open(SettingsActivity.PAGE_ACCESS) }
        }
    }

    /** The accent, and ambient mode with the wallpaper it shows: Index has no other wallpaper. */
    private fun look() {
        o.swatches(
            ctx.str(R.string.accent), ctx.str(R.string.accent_help),
            Accents.all.map { it.hex },
            { config.accent },
            {
                config.accent = it
                Tokens.applyAccent(it)
                // The focus rings here follow the new colour right away.
                o.restyle()
                rail.restyle()
            },
        )
        val minutes = listOf(2, 5, 10, 20).map { Option(ctx.activity.getString(R.string.minutes_short, it), it.toString()) }
        o.choice(
            ctx.str(R.string.ambient), ctx.str(R.string.ambient_help),
            listOf(Option(ctx.str(R.string.ambient_never), "0")) + minutes,
            { config.ambientMinutes.toString() },
            {
                val was = config.ambientMinutes > 0
                config.ambientMinutes = it.toInt()
                // The wallpaper rows come and go with ambient mode; other steps keep the page as it is.
                if (was != (config.ambientMinutes > 0)) rebuild(ctx.str(R.string.ambient))
            },
        )
        if (config.ambientMinutes > 0) o.nested(::wallpaper)
    }

    private fun wallpaper() {
        o.choice(
            ctx.str(R.string.wallpaper_source), ctx.str(R.string.wallpaper_source_help),
            listOf(
                Option(ctx.str(R.string.wallpaper_daily), "daily"),
                Option(ctx.str(R.string.wallpaper_reddit), REDDIT),
                Option(ctx.str(R.string.wallpaper_gradient), "gradient"),
                Option(ctx.str(R.string.wallpaper_custom), "custom"),
            ),
            { config.wallpaper },
            {
                val was = config.wallpaper == REDDIT
                config.wallpaper = it
                // The subreddit rows come and go with Reddit; other steps keep the page as it is.
                if (was != (it == REDDIT)) rebuild(ctx.str(R.string.wallpaper_source))
            },
        )
        o.link(ctx.str(R.string.gallery_title), ctx.str(R.string.gallery_help)) {
            ctx.activity.startActivity(android.content.Intent(ctx.activity, WallpaperGalleryActivity::class.java))
        }
        if (config.wallpaper != REDDIT) return
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
    }

    private fun home() {
        o.choice(
            ctx.str(R.string.app_sort), ctx.str(R.string.app_sort_help),
            listOf(Option(ctx.str(R.string.sort_usage), "usage"), Option(ctx.str(R.string.sort_manual), "manual"), Option(ctx.str(R.string.sort_alphabetical), "alphabetical")),
            { config.appSort }, { config.appSort = it },
        )
        o.page(ctx.str(R.string.settings_channels), ctx.str(R.string.settings_channels_help)) { open(SettingsActivity.PAGE_CHANNELS) }
        o.page(ctx.str(R.string.settings_visibility), ctx.str(R.string.settings_visibility_help)) { open(SettingsActivity.PAGE_APPS) }
        o.page(ctx.str(R.string.settings_inputs), ctx.str(R.string.settings_inputs_help)) { open(SettingsActivity.PAGE_INPUTS) }
        o.page(ctx.str(R.string.hass_title), ctx.str(R.string.hass_help), { homeAssistantValue() }) { open(SettingsActivity.PAGE_HOME_ASSISTANT) }
    }

    private fun homeAssistantValue(): String = when {
        config.homeAssistantUrl.isBlank() -> ctx.str(R.string.hass_not_connected)
        else -> ctx.activity.getString(R.string.hass_in_row_count, config.homeDevices.size, Config.MAX_HOME_DEVICES)
    }

    private fun clock() {
        o.toggle(ctx.str(R.string.clock24h), ctx.str(R.string.clock24h_help), { config.clock24h }, { config.clock24h = it })
        o.toggle(ctx.str(R.string.weather), ctx.str(R.string.weather_help), { config.weather }) {
            config.weather = it
            rebuild(ctx.str(R.string.weather))
        }
        if (config.weather) o.nested {
            val auto = ctx.str(R.string.city_auto)
            o.text(
                ctx.str(R.string.city), ctx.str(R.string.city_help), auto,
                { config.weatherCity.ifEmpty { auto } }, { config.weatherCity = it },
            )
            o.choice(
                ctx.str(R.string.units), ctx.str(R.string.units_help), listOf(Option("°C", "c"), Option("°F", "f")),
                { if (config.fahrenheit) "f" else "c" }, { config.fahrenheit = it == "f" },
            )
        }
    }

    private fun system() {
        o.page(ctx.str(R.string.group_setup), ctx.str(R.string.setup_page_help), { accessValue() }) { open(SettingsActivity.PAGE_ACCESS) }
        o.page(ctx.str(R.string.settings_tvs), ctx.str(R.string.settings_tvs_help), { ctx.str(if (config.syncEnabled) R.string.yes else R.string.no) }) {
            open(SettingsActivity.PAGE_TVS)
        }
        o.page(ctx.str(R.string.settings_phone), ctx.str(R.string.settings_phone_help), { phoneValue() }) { open(SettingsActivity.PAGE_PHONE) }
        o.page(ctx.str(R.string.remote_title), ctx.str(R.string.remote_link_help), { ctx.str(if (config.remoteButtons) R.string.yes else R.string.no) }) {
            open(RemotePage.PAGE)
        }
        about()
        if (config.godmode) godmodeRow()
        o.leaves(ctx.str(R.string.android_settings), ctx.str(R.string.android_settings_help)) {
            ctx.activity.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        updates()
    }

    private fun phoneValue(): String {
        val count = Sync.peers(ctx.activity).phones().size
        return if (count == 0) ctx.str(R.string.no) else count.toString()
    }

    private fun accessValue(): String {
        val off = Setup.items.count { !it.isOn(ctx.activity) }
        return if (off == 0) ctx.str(R.string.setup_all_on) else ctx.activity.resources.getQuantityString(R.plurals.setup_off, off, off)
    }

    /** The hidden page, once seven OKs on About found it. */
    private fun godmodeRow() {
        o.page(ctx.str(R.string.godmode), ctx.str(R.string.godmode_help)) { open(GodmodePage.page) }
    }

    /** True while the manual check runs; kept here so the status says so after switching groups. */
    private var checking = false

    /** Brings the update status and buttons up to date; null until the Couch group was shown. */
    private var showUpdate: (() -> Unit)? = null
    private var checkNowButton: View? = null

    /** Rawkoon TV's layout: the daily check, then what the last check found, then Check now and Install. */
    private fun updates() {
        o.header(ctx.str(R.string.updates_header))
        val toggle = o.toggle(ctx.str(R.string.check_updates), ctx.str(R.string.check_updates_help), { config.checkUpdates }, { config.checkUpdates = it })
        val activity = ctx.activity
        val fetcher = Fetcher(activity)
        val check = UpdateCheck(activity, fetcher)
        val status = TextView(activity).apply {
            textSize = 15f
            setTextColor(Tokens.textMuted)
            setPadding(activity.dp(20), activity.dp(6), activity.dp(20), activity.dp(10))
        }
        val checkNow = pill(ctx.str(R.string.update_check_now), accent = false)
        val install = pill("", accent = true)
        // The buttons end the page: Down stays put, Left from Check now goes back to the rail like a row.
        toggle.nextFocusDownId = checkNow.id
        checkNow.nextFocusLeftId = rail.item(Group.COUCH).id
        listOf(checkNow, install).forEach { it.nextFocusDownId = it.id }
        val buttons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(checkNow, LinearLayout.LayoutParams(WRAP_CONTENT, activity.dp(44)))
            addView(install, LinearLayout.LayoutParams(WRAP_CONTENT, activity.dp(44)).apply { marginStart = activity.dp(12) })
        }
        ctx.layout.list.addView(status)
        ctx.layout.list.addView(buttons, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            marginStart = activity.dp(20)
            bottomMargin = activity.dp(8)
        })
        val show = {
            val found = check.found()
            val date = { DateFormat.getMediumDateFormat(activity).format(check.checkedAt) }
            status.text = when {
                checking -> ctx.str(R.string.update_checking)
                Installer.running -> ctx.str(R.string.update_downloading)
                found != null -> activity.getString(R.string.update_available, found.version)
                check.checkedAt == 0L -> ctx.str(R.string.update_never)
                check.failed -> activity.getString(R.string.update_unreachable, date())
                else -> activity.getString(R.string.update_current, date())
            }
            val offer = found != null && !Installer.running
            // Focus would otherwise fall to whatever Android finds once the button is gone.
            if (!offer && install.hasFocus()) checkNow.requestFocus()
            install.visibility = if (offer) View.VISIBLE else View.GONE
            found?.let { install.text = activity.getString(R.string.update_install, it.version) }
            rail.refresh(Group.COUCH)
        }
        showUpdate = show
        checkNowButton = checkNow
        show()
        checkNow.setOnClickListener {
            if (checking || Installer.running) return@setOnClickListener
            checking = true
            show()
            thread(name = "update-check") {
                try {
                    check.refresh(config, force = true)
                } finally {
                    activity.runOnUiThread {
                        checking = false
                        if (!activity.isDestroyed) showUpdate?.invoke()
                    }
                }
            }
        }
        install.setOnClickListener {
            val found = check.found() ?: return@setOnClickListener
            Installer(activity, fetcher).install(found) { message ->
                message?.let { Toast.makeText(activity, it, Toast.LENGTH_LONG).show() }
                if (!activity.isDestroyed) showUpdate?.invoke()
            }
            show()
        }
    }

    /** A pill like the home screen's Update chip; [accent] marks the main action. */
    private fun pill(text: String, accent: Boolean) = TextView(ctx.activity).apply {
        this.text = text
        textSize = 16f
        gravity = Gravity.CENTER
        id = View.generateViewId()
        isFocusable = true
        isClickable = true
        setTextColor(if (accent) Tokens.accent else Tokens.textPrimary)
        background = if (accent) accentChip(ctx.activity) else plainChip(ctx.activity)
        setPadding(ctx.activity.dp(20), 0, ctx.activity.dp(20), 0)
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

    /** The group's options again, after a change that adds or removes rows; focus back on [focus]. */
    private fun rebuild(focus: String) = ctx.layout.list.post {
        show(current)
        refocus(focus)
    }

    private fun refocus(title: String?) {
        (o.rows().firstOrNull { it.label.text == title } ?: o.rows().firstOrNull())?.requestFocus()
    }

    private companion object {
        const val REDDIT = "reddit"
    }
}
