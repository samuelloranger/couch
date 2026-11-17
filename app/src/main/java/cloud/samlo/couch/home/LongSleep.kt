package cloud.samlo.couch.home

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import cloud.samlo.couch.HomeAccessibilityService
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.Prefs
import cloud.samlo.couch.data.UserFiles
import cloud.samlo.couch.godmode.IdleClose
import kotlin.concurrent.thread

/**
 * Once the TV has slept long enough, Couch closes what was left running and comes back in front,
 * so waking shows it with no flash of the app that was on screen. A timer set at screen-off does
 * it while the screen is still dark; waking redoes it when the timer never ran. The accessibility
 * service is the only piece of Couch that stays alive while another app is on screen and may
 * start an activity from the background, so it holds the screen receiver. The choice of hours
 * lives in `config.json` and is read on a worker; without that service the setting does nothing,
 * and the Permissions page says so.
 */
internal object LongSleep {
    private const val OFF_AT = "longSleep:offAt"

    /** True when a sleep that began at [offAt] and ends at [now] is long enough to act on. */
    fun due(offAt: Long, now: Long, minutes: Int): Boolean =
        minutes > 0 && offAt > 0L && now - offAt >= minutes * 60_000L

    /** Registers the screen receiver on the connected service; safe to call again. */
    fun attach(service: HomeAccessibilityService) {
        if (receiver != null) return
        val screen = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> fell(context)
                    Intent.ACTION_SCREEN_ON -> woke(context)
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            service.registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            service.registerReceiver(screen, filter)
        }
        receiver = screen
    }

    fun detach(service: HomeAccessibilityService) {
        receiver?.let { service.unregisterReceiver(it) }
        receiver = null
    }

    /** At boot: a restart ends any sleep the counter had begun. */
    fun forget(context: Context) {
        Prefs.of(context).edit().remove(OFF_AT).apply()
    }

    private var receiver: BroadcastReceiver? = null

    private fun fell(context: Context) {
        val app = context.applicationContext
        Prefs.of(app).edit().putLong(OFF_AT, System.currentTimeMillis()).apply()
        thread(name = "long-sleep", isDaemon = true) {
            val minutes = minutes(app)
            if (minutes <= 0) return@thread
            val alarms = app.getSystemService(AlarmManager::class.java) ?: return@thread
            // Not exact: a few minutes late changes nothing, and inexact needs no permission.
            alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + minutes * 60_000L, alarm(app))
        }
    }

    /** Still asleep once the hours have passed: come home and close the rest in the dark, so waking shows Couch. */
    internal fun elapsed(context: Context) {
        val app = context.applicationContext
        if (app.getSystemService(PowerManager::class.java)?.isInteractive != false) return
        val prefs = Prefs.of(app)
        val offAt = prefs.getLong(OFF_AT, 0L)
        // Without the service Couch can't come to the front; leave the stamp for the wake to retry.
        if (!due(offAt, System.currentTimeMillis(), minutes(app)) || HomeAccessibilityService.context() == null) return
        prefs.edit().remove(OFF_AT).commit()
        comeHome(app)
    }

    /** The timer can't have run (deep standby held it, or the hours changed): do it now, as the TV wakes. */
    private fun woke(context: Context) {
        val app = context.applicationContext
        app.getSystemService(AlarmManager::class.java)?.cancel(alarm(app))
        val prefs = Prefs.of(app)
        val offAt = prefs.getLong(OFF_AT, 0L)
        prefs.edit().remove(OFF_AT).apply()
        if (offAt == 0L) return
        thread(name = "long-sleep", isDaemon = true) {
            if (due(offAt, System.currentTimeMillis(), minutes(app))) comeHome(app)
        }
    }

    /** Blocking: call from a worker. */
    private fun comeHome(app: Context) {
        // Couch first: the app that was on screen then becomes a background process, closed with
        // the rest instead of staying as the one Android keeps for the next wake.
        Handler(Looper.getMainLooper()).post { HomeAccessibilityService.showHome() }
        Thread.sleep(SETTLE_MS)
        runCatching { IdleClose.closeAll(app) }
    }

    private fun minutes(app: Context) = runCatching { Config.load(UserFiles(app).dir).longSleepMinutes }.getOrDefault(0)

    private fun alarm(context: Context): PendingIntent =
        PendingIntent.getBroadcast(context, 0, Intent(context, LongSleepReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)

    /** Long enough for Couch to be in front before its predecessor is closed. */
    private const val SETTLE_MS = 2_000L
}

/** The timer set when the screen went off. */
class LongSleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread {
            try {
                runCatching { LongSleep.elapsed(context) }
            } finally {
                pending.finish()
            }
        }
    }
}
