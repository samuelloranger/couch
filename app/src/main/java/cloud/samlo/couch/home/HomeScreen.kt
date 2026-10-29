package cloud.samlo.couch.home

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import cloud.samlo.couch.HomeAccessibilityService
import cloud.samlo.couch.MediaListener
import cloud.samlo.couch.R
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.Fetcher
import cloud.samlo.couch.data.Images
import cloud.samlo.couch.data.InputSource
import cloud.samlo.couch.data.Prefs
import cloud.samlo.couch.data.Programs
import cloud.samlo.couch.data.UserFiles
import cloud.samlo.couch.data.Wallpapers
import cloud.samlo.couch.data.Weather
import cloud.samlo.couch.data.WeatherNow
import cloud.samlo.couch.godmode.GodmodePage
import cloud.samlo.couch.godmode.Konami
import cloud.samlo.couch.godmode.RealHome
import cloud.samlo.couch.godmode.TvSettings
import cloud.samlo.couch.measure.Benchmark
import cloud.samlo.couch.measure.BenchmarkCard
import cloud.samlo.couch.measure.FrameHud
import cloud.samlo.couch.settings.SettingsActivity
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.update.Installer
import cloud.samlo.couch.update.Release
import cloud.samlo.couch.update.UpdateCheck

/**
 * The home screen's wiring and lifecycle: it owns the pieces (loader, menus, ambient timer) and
 * forwards the activity's callbacks to them. Create it in `onCreate`.
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
    private var resumedAt = 0L

    private val ambientMode = AmbientMode(views.ambient) { resumed }
        .also { it.minutes = config.ambientMinutes }
    private val menus: CardMenus = CardMenus(activity, { config }, files) { load() }
    private val loader = HomeLoader(
        activity, files, Wallpapers(fetcher, prefs), Weather(fetcher, prefs), UpdateCheck(activity, fetcher), ::onLoaded,
    )
    private val watchers = SystemWatchers(activity, { load() }) { offerInput(it) }
    private val reloadForRecommendations = Runnable { if (resumed) load() }
    private val hud = FrameHud(activity)
    private val benchmark = Benchmark(activity)
    private var card: android.view.View? = null
    private val konami = Konami()
    /** The OK that completed the Konami code: its key-up is swallowed too, so no card opens. */
    private var swallowUp = false

    fun onCreate() {
        activity.setContentView(views.root)
        watchers.register()
        if (!Programs.canRead(activity)) activity.requestPermissions(arrayOf(Programs.READ_TV_LISTINGS), 1)
    }

    fun onResume() {
        resumed = true
        swallowUp = false
        resumedAt = SystemClock.elapsedRealtime()
        MediaListener.onRecommendationsChanged = {
            // Apps often post a batch at once: one reload for all of them.
            main.removeCallbacks(reloadForRecommendations)
            main.postDelayed(reloadForRecommendations, RECOMMENDATIONS_SETTLE_MS)
        }
        HomeAccessibilityService.restore(activity)
        // Watch progress moves while you're in an app; unchanged rows are not rebuilt.
        load()
        startLive()
        ambientMode.schedule(activity = true)
    }

    fun onPause() {
        resumed = false
        MediaListener.onRecommendationsChanged = null
        main.removeCallbacks(reloadForRecommendations)
        hud.enable(Config.HUD_OFF)
        benchmark.cancel()
        closeCard()
        swallowUp = false
        stopLive()
        ambientMode.cancel()
        views.index.topBar.inputOffer.hide()
    }

    fun onDestroy() {
        watchers.unregister()
        loader.shutdown()
    }

    /** HOME while already home goes back to the start, like the stock launcher. */
    fun onHomePressed() {
        if (!resumed) return
        benchmark.cancel()
        views.index.reset()
    }

    fun load() = loader.load()

    /**
     * True when the key was consumed (waking from ambient, a benchmark or its card, the Index panels).
     * The HUD sees every press first, before anything can swallow it.
     */
    fun consumesKey(event: KeyEvent): Boolean {
        hud.onKey(event)
        card?.let {
            if (event.action == KeyEvent.ACTION_UP) closeCard()
            return true
        }
        if (konamiConsumes(event)) return true
        return benchmark.abortOn(event) || ambientMode.consumesKey(event) || views.index.consumesKey(event)
    }

    private fun konamiConsumes(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && swallowUp) {
            swallowUp = false
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0 || !konami.press(event.keyCode, event.eventTime)) return false
        swallowUp = true
        if (!config.godmode) {
            config.godmode = true
            config.save(files.dir)
        }
        activity.startActivity(SettingsActivity.intent(activity, GodmodePage.page))
        return true
    }

    /** Godmode's benchmark: back to the first card, then the script; the result shows over home. */
    fun runBenchmark() {
        // Android delivers the intent before onResume, and the reset needs the screen resumed.
        main.postDelayed({ startBenchmark() }, BENCHMARK_SETTLE_MS)
    }

    private fun startBenchmark() {
        if (benchmark.running || !resumed) return
        closeCard()
        onHomePressed()
        benchmark.run { result ->
            if (result == null) return@run
            // The HUD's panel sits where the card's corner lands; it comes back with the card gone.
            hud.enable(Config.HUD_OFF)
            card = BenchmarkCard.show(activity, result, Benchmark.last(activity), config.hud == Config.HUD_FPS)
            Benchmark.save(activity, result)
        }
    }

    /** The HUD only runs while the home screen is up and godmode is unlocked. */
    private fun hudUnit() = if (resumed && config.godmode) config.hud else Config.HUD_OFF

    private fun closeCard() {
        card?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) } ?: return
        card = null
        hud.enable(hudUnit())
    }

    private fun onLoaded(data: HomeData) {
        val accentChanged = config.accent != data.config.accent
        config = data.config
        if (accentChanged) Tokens.applyAccent(config.accent)
        views.ambient.setTimeFormat(if (config.clock24h) "HH:mm" else "h:mm")
        // Not while the result card is up: the panel would sit under it.
        if (card == null) hud.enable(hudUnit())
        // Godmode locked costs one boolean here; unlocked, a couple of cached settings reads.
        val god = config.godmode && (config.hud != Config.HUD_OFF || TvSettings.changed(activity) > 0 || RealHome.active(activity))
        views.index.topBar.god.show(god)
        ambientMode.minutes = config.ambientMinutes
        ambientMode.pictures = data.pictures
        showWeather(data.weather)
        update = data.update
        // A reload mid-download keeps the "Downloading" label.
        if (!Installer.running) {
            views.index.topBar.update.show(update?.version)
        }
        views.index.bind(data.index, config)
        ambientMode.schedule()
    }

    private fun startLive() {
        views.index.topBar.nowPlaying.start()
        views.index.systemLine.start()
        views.index.topBar.animateWeather(true)
    }

    private fun stopLive() {
        views.index.topBar.nowPlaying.stop()
        views.index.systemLine.stop()
        views.index.topBar.animateWeather(false)
    }

    private fun showWeather(now: WeatherNow?) {
        views.index.topBar.showWeather(now)
        ambientMode.weatherLine = now?.let {
            val label = if (it.condition.label != 0) activity.getString(it.condition.label) else ""
            activity.getString(R.string.weather_line, it.temperature, label, it.place)
        }
    }

    private fun installUpdate() {
        val release = update ?: return
        if (Installer.running) return
        views.index.topBar.update.downloading()
        installer.install(release) { message ->
            if (message != null) Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            views.index.topBar.update.show(release.version)
        }
    }

    /** A device on an input just turned on: while home shows, offer to switch to it (unless hidden). */
    private fun offerInput(inputId: String) {
        // A TV waking up reports its inputs changing as it comes back; those are no news.
        if (!resumed || SystemClock.elapsedRealtime() - resumedAt < WAKE_SETTLE_MS) return
        val tile = InputSource.tiles(activity, config, null).firstOrNull { it.inputId == inputId } ?: return
        views.index.topBar.inputOffer.offer(tile)
    }

    private fun openSettings() {
        activity.startActivity(Intent(activity, SettingsActivity::class.java))
    }

    private companion object {
        const val RECOMMENDATIONS_SETTLE_MS = 2_000L
        const val WAKE_SETTLE_MS = 10_000L
        const val BENCHMARK_SETTLE_MS = 700L
    }
}
