package cloud.samlo.couch.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import cloud.samlo.couch.HomeAccessibilityService
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.Prefs
import cloud.samlo.couch.data.UserFiles
import cloud.samlo.couch.godmode.IdleClose
import kotlin.concurrent.thread

/**
 * Waking the TV after a long sleep closes what was left running and puts Couch back in front.
 * The accessibility service is the only piece of Couch that stays alive while another app is on
 * screen and may start an activity from the background, so it holds the screen receiver. The
 * choice of hours lives in `config.json` and is read when the TV wakes, on a worker; without that
 * service the setting does nothing, and the Permissions page says so.
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
        Prefs.of(context).edit().putLong(OFF_AT, System.currentTimeMillis()).apply()
    }

    private fun woke(context: Context) {
        val app = context.applicationContext
        val prefs = Prefs.of(app)
        val offAt = prefs.getLong(OFF_AT, 0L)
        prefs.edit().remove(OFF_AT).apply()
        if (offAt == 0L) return
        thread(name = "long-sleep", isDaemon = true) {
            val minutes = runCatching { Config.load(UserFiles(app).dir).longSleepMinutes }.getOrDefault(0)
            if (!due(offAt, System.currentTimeMillis(), minutes)) return@thread
            // Couch first: the app that was on screen then becomes a background process, closed with
            // the rest instead of staying as the one Android keeps for the next wake.
            Handler(Looper.getMainLooper()).post { HomeAccessibilityService.showHome() }
            Thread.sleep(SETTLE_MS)
            runCatching { IdleClose.closeAll(app) }
        }
    }

    /** Long enough for Couch to be in front before its predecessor is closed. */
    private const val SETTLE_MS = 2_000L
}
