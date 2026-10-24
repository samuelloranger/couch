package cloud.samlo.couch.godmode

import android.app.Activity
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import android.widget.Toast
import cloud.samlo.couch.R
import cloud.samlo.couch.adb.AdbShell
import cloud.samlo.couch.debloat.Engine
import kotlin.concurrent.thread

/**
 * Clears every app's cache over the shell (`pm trim-caches`), which on a full TV frees far more
 * than removing built-in apps can: they live on the read-only system partition. Apps rebuild
 * their caches as they go, so it's safe and it's temporary.
 */
internal object FreeCache {
    fun freeBytes(): Long = StatFs(Environment.getDataDirectory().path).availableBytes

    fun free(activity: Activity) = Formatter.formatShortFileSize(activity, freeBytes())

    /** Runs on a worker; [done] comes back on the UI thread once there's something to say. */
    fun run(activity: Activity, engine: Engine, done: () -> Unit) = thread {
        val message = freed(activity, engine)
        activity.runOnUiThread {
            if (!activity.isFinishing) Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            done()
        }
    }

    private fun freed(activity: Activity, engine: Engine): String =
        when (engine.connect()) {
            Engine.State.GRANTED -> try {
                val before = freeBytes()
                engine.trimCaches()
                val gained = freeBytes() - before
                if (gained < MEANINGFUL) activity.getString(R.string.cache_nothing)
                else activity.getString(R.string.cache_freed, Formatter.formatShortFileSize(activity, gained))
            } catch (e: AdbShell.Failed) {
                // The shell dropped or timed out mid-command: this thread is the launcher's process.
                activity.getString(R.string.shell_no_ok)
            }
            Engine.State.BLOCKED -> activity.getString(R.string.cache_by_hand)
            Engine.State.DEBUG_OFF -> activity.getString(R.string.debug_off_error)
            else -> activity.getString(R.string.shell_no_ok)
        }

    /** Below a megabyte the counter only moves because something else wrote meanwhile. */
    private const val MEANINGFUL = 1024L * 1024L
}
