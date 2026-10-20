package cloud.samlo.couch.home

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.Toast
import cloud.samlo.couch.HomeAccessibilityService
import cloud.samlo.couch.R
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.Fetcher
import cloud.samlo.couch.data.Images
import cloud.samlo.couch.data.Prefs
import cloud.samlo.couch.data.Programs
import cloud.samlo.couch.data.UserFiles
import cloud.samlo.couch.data.Wallpapers
import cloud.samlo.couch.data.Weather
import cloud.samlo.couch.data.WeatherNow
import cloud.samlo.couch.settings.SettingsActivity
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.update.Installer
import cloud.samlo.couch.update.Release
import cloud.samlo.couch.update.UpdateCheck

/**
 * The home screen's wiring and lifecycle: it owns the pieces (loader, binder, organizer, menus,
 * ambient timer) and forwards the activity's callbacks to them. Create it in `onCreate`.
 */
class HomeScreen(private val activity: Activity) {
    private val files = UserFiles(activity)
    private val prefs = Prefs.of(activity)
    private val fetcher = Fetcher(activity)
    private val images = Images(activity, fetcher)
    private val views: HomeViews = HomeViews(
        activity, images, { openSettings() }, { installUpdate() },
        onAppMenu = { menus.show(it) },
        onProgramMenu = { menus.show(it) },
    )
    private val installer = Installer(activity, fetcher)
    private var update: Release? = null
    private val main = Handler(Looper.getMainLooper())

    private var config = Config.load(null)
    private var resumed = false

    private val ambientMode = AmbientMode(views.ambient, { resumed }, { organizer.active })
        .also { it.minutes = config.ambientMinutes }
    private val organizer: Organizer = Organizer(activity, views.rows, views.hero, { config }, files) { load() }
    private val menus: CardMenus = CardMenus(activity, { config }, files, { load() }) { organizer.start(it) }
    private val binder = SectionBinder(
        activity, views.rows, images,
        isOrganizing = { organizer.active },
        onFocused = { card -> if (organizer.active) organizer.hint() else views.hero.show(card.tile) },
        onLongPress = { menus.show(it) },
    )
    private val loader = HomeLoader(
        activity, files, Wallpapers(fetcher, prefs), Weather(fetcher, prefs), UpdateCheck(activity, fetcher), ::onLoaded,
    )
    private val watchers = SystemWatchers(activity, views.rows) { load() }

    fun onCreate() {
        activity.setContentView(views.root)
        watchers.register()
        if (!Programs.canRead(activity)) activity.requestPermissions(arrayOf(Programs.READ_TV_LISTINGS), 1)
    }

    fun onResume() {
        resumed = true
        HomeAccessibilityService.restore(activity)
        // Watch progress moves while you're in an app; unchanged rows are not rebuilt.
        load()
        startLive()
        ambientMode.schedule(activity = true)
    }

    fun onPause() {
        resumed = false
        if (organizer.active) organizer.stop()
        stopLive()
        ambientMode.cancel()
    }

    fun onDestroy() {
        watchers.unregister()
        loader.shutdown()
    }

    /** HOME while already home goes back to the start, like the stock launcher. */
    fun onHomePressed() {
        if (!resumed) return
        if (views.indexShown) {
            views.index.reset()
            return
        }
        views.rows.reset()
        views.rows.sections().firstOrNull()?.row?.focusFirst()
    }

    fun onSettingsResult(resultCode: Int) {
        if (resultCode == SettingsActivity.RESULT_ORGANIZE && !views.indexShown) main.post { organizer.start() }
    }

    fun load() = loader.load()

    /** True when the key was consumed (waking from ambient, or reorder mode). */
    fun consumesKey(event: KeyEvent) = ambientMode.consumesKey(event) || organizer.consumesKey(event) ||
        (views.indexShown && views.index.consumesKey(event))

    private fun onLoaded(data: HomeData) {
        val lookChanged = config.look != data.config.look
        config = data.config
        if (lookChanged) Tokens.applyAccent(config.accent)
        applyLook(config)
        views.hero.wallpaperUrl = data.wallpaper
        ambientMode.minutes = config.ambientMinutes
        ambientMode.pictures = data.pictures
        showWeather(data.weather)
        update = data.update
        // A reload mid-download keeps the "Downloading" label.
        if (!Installer.running) {
            views.topBar.update.show(update?.version)
            views.index.topBar.update.show(update?.version)
        }
        val index = data.index
        switchLayout(index != null)
        if (index != null) views.index.bind(index, config) else binder.bind(data.sections, config)
        ambientMode.schedule()
    }

    /** Swaps layouts after a settings change; the live parts (now playing, readings) follow. */
    private fun switchLayout(index: Boolean) {
        if (index == views.indexShown) return
        if (resumed) stopLive()
        views.showIndex(index)
        // The hidden rows would keep every card and its art in memory; they rebuild on the way back.
        if (index) views.rows.column.removeAllViews()
        if (resumed) startLive()
    }

    private fun startLive() {
        if (views.indexShown) {
            views.index.topBar.nowPlaying.start()
            views.index.systemLine.start()
            views.index.topBar.animateWeather(true)
        } else {
            views.topBar.nowPlaying.start()
            views.topBar.animateWeather(true)
        }
    }

    private fun stopLive() {
        views.topBar.nowPlaying.stop()
        views.topBar.animateWeather(false)
        views.index.topBar.nowPlaying.stop()
        views.index.systemLine.stop()
        views.index.topBar.animateWeather(false)
    }

    /** Settings that change existing views rather than cards. */
    private fun applyLook(config: Config) {
        views.hero.setDim(config.dim)
        views.hero.setShowDescription(config.showDescriptions)
        val time = if (config.clock24h) "HH:mm" else "h:mm"
        views.topBar.applyLook(config, time)
        views.ambient.setTimeFormat(time)
    }

    private fun showWeather(now: WeatherNow?) {
        views.topBar.showWeather(now)
        views.index.topBar.showWeather(now)
        ambientMode.weatherLine = now?.let {
            val label = if (it.condition.label != 0) activity.getString(it.condition.label) else ""
            activity.getString(R.string.weather_line, it.temperature, label, it.place)
        }
    }

    private fun installUpdate() {
        val release = update ?: return
        if (Installer.running) return
        views.topBar.update.downloading()
        views.index.topBar.update.downloading()
        installer.install(release) { message ->
            if (message != null) Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            views.topBar.update.show(release.version)
            views.index.topBar.update.show(release.version)
        }
    }

    @Suppress("DEPRECATION")
    private fun openSettings() {
        activity.startActivityForResult(Intent(activity, SettingsActivity::class.java), 1)
    }
}
