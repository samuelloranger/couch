package cloud.samlo.couch.godmode

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import cloud.samlo.couch.adb.AdbShell
import cloud.samlo.couch.data.Prefs
import cloud.samlo.couch.data.Usage
import kotlin.concurrent.thread

/**
 * Closes apps nobody has opened for a while. Every quarter of an hour an alarm checks the apps on
 * the home screen against Android's usage events and closes the ones last used longer ago than
 * the chosen limit, the way Android would under memory pressure: what is on screen, and music
 * still playing, are never touched. Needs usage access; without it nothing is closed.
 */
internal object IdleClose {
    private const val MINUTES = "godmode:idleCloseMinutes"
    private const val EVERY_MS = AlarmManager.INTERVAL_FIFTEEN_MINUTES

    fun minutes(context: Context): Int = Prefs.of(context).getInt(MINUTES, 0)

    fun set(context: Context, minutes: Int) {
        Prefs.of(context).edit().putInt(MINUTES, minutes).apply()
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        alarms.cancel(alarm(context))
        if (minutes > 0) schedule(context)
    }

    /** At boot and on every return home: alarms don't survive a restart or a force-stop. */
    fun arm(context: Context) {
        if (minutes(context) > 0 && !armed(context)) schedule(context)
    }

    /** Closes what has been idle past the limit. Blocking: call from a worker. */
    fun sweep(context: Context) {
        val minutes = minutes(context)
        if (minutes <= 0) return
        val used = Usage.lastWeek(context) ?: return
        val idle = idle(launchable(context), used.mapValues { it.value.lastUsed }, System.currentTimeMillis(), minutes, context.packageName)
        if (idle.isEmpty()) return
        // Android 14 lets an app close only its own processes; the shell may still close the rest.
        if (Build.VERSION.SDK_INT < 34) {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            idle.forEach { am.killBackgroundProcesses(it) }
        } else {
            shell(context, idle.joinToString("; ") { "am kill $it" })
        }
    }

    /**
     * The apps to close: launchable ones, not Couch, last used before [minutes] ago. One with no
     * use in the past week has no entry in [lastUsed] and counts as idle.
     */
    fun idle(apps: Collection<String>, lastUsed: Map<String, Long>, now: Long, minutes: Int, self: String): List<String> {
        val before = now - minutes * 60_000L
        return apps.filter { it != self && (lastUsed[it] ?: 0L) < before }.sorted()
    }

    private fun launchable(context: Context): Set<String> {
        val pm = context.packageManager
        return listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER).flatMapTo(HashSet()) { category ->
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), PackageManager.MATCH_ALL).map { it.activityInfo.packageName }
        }
    }

    private fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        // Not a wakeup: while the TV sleeps nothing changes, and the first check after it wakes is soon enough.
        alarms.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + EVERY_MS, EVERY_MS, alarm(context))
    }

    private fun alarm(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 0, receiver(context), PendingIntent.FLAG_IMMUTABLE)

    /** A force-stop or a restart drops the alarm and its intent together. */
    private fun armed(context: Context) =
        PendingIntent.getBroadcast(context, 0, receiver(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE) != null

    private fun receiver(context: Context) = Intent(context, IdleCloseReceiver::class.java)

    // Failing (debugging off, key no longer allowed) is fine: the next check tries again.
    private fun shell(context: Context, command: String) {
        AdbShell.once(context) { it.run(command) }
    }
}

/** The quarter-hourly check. */
class IdleCloseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread {
            try {
                runCatching { IdleClose.sweep(context.applicationContext) }
            } finally {
                pending.finish()
            }
        }
    }
}
