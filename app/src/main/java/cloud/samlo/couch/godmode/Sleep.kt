package cloud.samlo.couch.godmode

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import cloud.samlo.couch.HomeAccessibilityService
import cloud.samlo.couch.adb.AdbShell
import cloud.samlo.couch.data.Prefs
import kotlin.concurrent.thread

/**
 * Godmode's sleep timer and screenshot. Both prefer the shell ("input keyevent KEYCODE_SLEEP",
 * "screencap"), which works on any TV that granted Couch, and fall back to Couch's accessibility
 * service. The timer is an alarm, so it fires even after Couch's screens have closed.
 */
internal object Sleep {
    private const val MINUTES = "godmode:sleepMinutes"
    private const val AT = "godmode:sleepAt"
    /**
     * The window asked for; Android 12 and later may widen it for apps without exact-alarm rights,
     * so the TV can go to sleep a few minutes late. A sleep timer doesn't need the second.
     */
    private const val WINDOW_MS = 60_000L

    /** The chosen minutes while a timer is pending, else 0. */
    fun minutes(context: Context): Int {
        val prefs = Prefs.of(context)
        return if (prefs.getLong(AT, 0L) > SystemClock.elapsedRealtime()) prefs.getInt(MINUTES, 0) else 0
    }

    fun set(context: Context, minutes: Int) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(context, 0, Intent(context, SleepReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
        alarms.cancel(intent)
        val at = SystemClock.elapsedRealtime() + minutes * 60_000L
        Prefs.of(context).edit().putInt(MINUTES, minutes).putLong(AT, if (minutes > 0) at else 0L).apply()
        if (minutes > 0) alarms.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, WINDOW_MS, intent)
    }

    /** At boot: alarms don't survive a restart, so neither does the pending timer. */
    fun forget(context: Context) {
        Prefs.of(context).edit().remove(AT).apply()
    }

    /** Puts the TV to standby now. Blocking: call from a worker. */
    fun now(context: Context) {
        Prefs.of(context).edit().remove(AT).apply()
        if (!shell(context, "input keyevent KEYCODE_SLEEP")) HomeAccessibilityService.globalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
    }

    /** Where a screenshot ended up: Couch's own file, the TV's own screenshot, or nowhere. */
    enum class Shot { TO_PICTURES, BY_THE_TV, FAILED }

    /**
     * Saves what's on screen. The shell writes it to Pictures under [name], once the file exists
     * and isn't empty; where the shell can't be had silently, Android's own screenshot takes it
     * instead, and that one picks its own name and folder.
     */
    // The path is the shell's, not the app's: /sdcard is where the shell user writes Pictures.
    @SuppressLint("SdCardPath")
    fun screenshot(context: Context, name: String): Shot {
        val path = "/sdcard/Pictures/$name"
        val out = output(context, "mkdir -p /sdcard/Pictures && screencap -p $path && [ -s $path ] && echo saved")
        return when {
            out?.contains("saved") == true -> Shot.TO_PICTURES
            HomeAccessibilityService.globalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) -> Shot.BY_THE_TV
            else -> Shot.FAILED
        }
    }

    private fun shell(context: Context, command: String) = output(context, command) != null

    /** The command's output; null on any failure (no shell, a key that can't be read): these run on bare threads. */
    private fun output(context: Context, command: String): String? {
        val shell = try {
            AdbShell.create(context, prompt = false)
        } catch (e: Exception) {
            // Reading or generating the key failed; the callers here run on bare threads.
            return null
        }
        return try {
            shell.run(command)
        } catch (e: Exception) {
            null
        } finally {
            shell.close()
        }
    }
}

/** The sleep timer's alarm. */
class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread {
            try {
                Sleep.now(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
