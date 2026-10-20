package cloud.samlo.couch.home

import android.content.Context
import android.os.Handler
import android.os.Looper
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.UserFiles
import cloud.samlo.couch.data.Wallpapers
import cloud.samlo.couch.data.Weather
import cloud.samlo.couch.data.WeatherNow
import cloud.samlo.couch.index.IndexBuilder
import cloud.samlo.couch.index.IndexData
import cloud.samlo.couch.update.Release
import cloud.samlo.couch.update.UpdateCheck
import java.util.concurrent.Executors

/** Everything one reload produced, ready to apply on the main thread. */
class HomeData(
    val config: Config,
    val wallpaper: String?,
    val pictures: List<String>,
    val weather: WeatherNow?,
    val sections: List<SectionData>,
    /** The Index layout's apps, when that is the chosen layout. */
    val index: IndexData?,
    /** A newer release, when the daily check found one. */
    val update: Release?,
)

/**
 * Builds [HomeData] on a single background thread and hands it to [onLoaded] on the main one.
 * A reload requested while one is running is queued and runs once, right after it.
 */
class HomeLoader(
    private val context: Context,
    private val files: UserFiles,
    private val wallpapers: Wallpapers,
    private val weather: Weather,
    private val updates: UpdateCheck,
    private val onLoaded: (HomeData) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor()
    /** The daily update check, apart so a slow GitHub never delays the rows. */
    private val checker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var loading = false
    private var reloadQueued = false

    fun load() {
        if (loading) {
            reloadQueued = true
            return
        }
        loading = true
        checker.execute { if (updates.refresh(Config.load(files.dir))) main.post { load() } }
        executor.execute {
            val data = build()
            main.post {
                onLoaded(data)
                loading = false
                if (reloadQueued) {
                    reloadQueued = false
                    load()
                }
            }
        }
    }

    fun shutdown() {
        executor.shutdown()
        checker.shutdown()
    }

    private fun build(): HomeData {
        val config = Config.load(files.dir)
        // Only the chosen layout's content is built.
        val index = if (config.layout == Config.LAYOUT_INDEX) IndexBuilder.build(context, config, files.icons) else null
        val sections = if (index == null) SectionBuilder.build(context, config, files.icons) else emptyList()
        val wallpaper = wallpapers.current(config, files.dir)
        val pictures = if (config.ambientMinutes > 0) wallpapers.all(config, files.dir) else emptyList()
        return HomeData(config, wallpaper, pictures, weather.now(config), sections, index, updates.pending(config))
    }
}
