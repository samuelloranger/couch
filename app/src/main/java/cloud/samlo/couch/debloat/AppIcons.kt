package cloud.samlo.couch.debloat

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import cloud.samlo.couch.data.Banners
import java.util.concurrent.Executors

/**
 * Icons for the grid, decoded on one worker at the tile's exact pixel size, so drawing them is a
 * plain copy. Only apps someone can open get their own icon: background services all carry
 * Android's default robot, which would make a grid of identical tiles, so they get none and the
 * tile draws its kind instead.
 */
class AppIcons(context: Context) {
    private val app = context.applicationContext
    private val done = HashMap<String, Bitmap?>()

    /** [show] runs on the worker with the icon, or not at all when the app has none worth showing. */
    fun load(pkg: String, px: Int, radius: Float, show: (Bitmap) -> Unit) {
        worker.execute {
            val icon = done.getOrPut(pkg) { render(pkg, px, radius) } ?: return@execute
            show(icon)
        }
    }

    private fun render(pkg: String, px: Int, radius: Float): Bitmap? = try {
        val pm = app.packageManager
        val openable = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        openable?.let { Banners.render(pm.getApplicationIcon(pkg), px, px, radius) }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private companion object {
        /** One thread for every page that shows icons, so opening the page again adds none. */
        val worker = Executors.newSingleThreadExecutor { Thread(it, "debloat-icons").apply { isDaemon = true } }
    }
}
