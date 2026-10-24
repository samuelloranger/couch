package cloud.samlo.couch.debloat

import android.content.Context
import cloud.samlo.couch.data.Prefs
import kotlin.concurrent.thread

/**
 * Android forgets an "Always allow" key after 7 days without a connection, and the question comes
 * back. Once the shell was granted, the home screen calls [maybe] on resume: at most once a day it
 * reconnects silently in the background. A key Android already dropped is not asked about again
 * until the user reconnects from godmode. Never granted, or godmode hidden: one boolean read.
 */
object Keepalive {
    private const val GRANTED = "adbGranted"
    private const val TOUCHED = "adbTouchedAt"
    private const val EVERY_MS = 24L * 60 * 60 * 1000

    fun granted(context: Context) {
        Prefs.of(context).edit().putBoolean(GRANTED, true).putLong(TOUCHED, System.currentTimeMillis()).apply()
    }

    /** Whether a key was ever allowed, so a silent connection is worth trying. */
    fun isGranted(context: Context) = Prefs.of(context).getBoolean(GRANTED, false)

    fun forget(context: Context) {
        Prefs.of(context).edit().remove(GRANTED).remove(TOUCHED).apply()
    }

    fun maybe(context: Context) {
        val prefs = Prefs.of(context)
        if (!prefs.getBoolean(GRANTED, false)) return
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(TOUCHED, 0L) < EVERY_MS) return
        prefs.edit().putLong(TOUCHED, now).apply()
        val app = context.applicationContext
        thread(name = "adb-keepalive", isDaemon = true) {
            // Nothing here may escape: this thread is the launcher's process.
            runCatching { if (Engine(app).touch() == false) forget(app) }
        }
    }
}
