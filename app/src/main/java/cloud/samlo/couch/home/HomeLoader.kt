package cloud.samlo.couch.home

import android.content.Context
import android.os.Handler
import android.os.Looper
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.CustomWallpapers
import cloud.samlo.couch.data.UserFiles
import cloud.samlo.couch.data.Wallpapers
import cloud.samlo.couch.data.Weather
import cloud.samlo.couch.data.WeatherNow
import cloud.samlo.couch.index.IndexBuilder
import cloud.samlo.couch.index.IndexData
import cloud.samlo.couch.update.Release
import cloud.samlo.couch.update.UpdateCheck
import java.util.concurrent.Executors

/** The local half of a reload, ready to apply on the main thread without waiting for the network. */
class HomeData(
    val config: Config,
    val index: IndexData,
    /** A newer release, when the daily check found one. */
    val update: Release?,
)

/** The network half of a reload: ambient pictures and weather, which may take seconds or fail. */
class HomeExtras(
    val pictures: List<String>,
    val weather: WeatherNow?,
)

/**
 * Builds [HomeData] on a single background thread and hands it to [onLoaded] on the main one,
 * then the slower [HomeExtras] to [onExtras]. A reload requested while one is running is queued and runs once, right after it.
 */
class HomeLoader(
    private val context: Context,
    private val files: UserFiles,
    private val wallpapers: Wallpapers,
    private val weather: Weather,
    private val updates: UpdateCheck,
    private val onLoaded: (HomeData) -> Unit,
    private val onExtras: (HomeExtras) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor()
    /** The daily update check, apart so a slow GitHub never delays the apps. */
    private val checker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var loading = false
    private var reloadQueued = false
    /** Set by [shutdown]: late posts from the update check must not touch the dead executors. */
    private var closed = false
    /** Touched only on [executor]: whether the apps have been posted once. */
    private var shownOnce = false

    /** A local-only scan for the active slideshow, without rebuilding the home screen. */
    fun customPictures(onReady: (List<String>) -> Unit) {
        if (closed) return
        executor.execute {
            val pictures = CustomWallpapers.paths(files.dir)
            main.post { onReady(pictures) }
        }
    }

    fun load() {
        if (closed) return
        if (loading) {
            reloadQueued = true
            return
        }
        loading = true
        checker.execute { if (updates.refresh(Config.load(files.dir))) main.post { load() } }
        executor.execute {
            val config = Config.load(files.dir)
            val apps = IndexBuilder.apps(context, config, files.icons)
            val update = updates.pending(config)
            // The first load shows the apps before the channels, which take about a second on a weak TV.
            // Later ones wait for both, so a return home never blanks the cards on show.
            if (!shownOnce) {
                shownOnce = true
                main.post { onLoaded(HomeData(config, apps, update)) }
            }
            val data = HomeData(config, IndexBuilder.withChannels(context, config, apps), update)
            main.post { onLoaded(data) }
            val pictures = if (config.ambientMinutes > 0) wallpapers.all(config, files.dir) else emptyList()
            val extras = HomeExtras(pictures, weather.now(config))
            main.post {
                onExtras(extras)
                loading = false
                if (reloadQueued) {
                    reloadQueued = false
                    load()
                }
            }
        }
    }

    fun shutdown() {
        closed = true
        executor.shutdown()
        checker.shutdown()
    }
}
