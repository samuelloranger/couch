package cloud.samlo.couch.godmode

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import cloud.samlo.couch.R
import cloud.samlo.couch.debloat.Engine
import cloud.samlo.couch.settings.Option
import cloud.samlo.couch.settings.OptionRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** The sleep timer, a delayed screenshot, and making Couch the TV's real home. */
internal fun GodmodePage.toolRows() {
    val c = ctx.activity
    val minutes = listOf(0, 15, 30, 60, 90, 120)
    val options = minutes.map { Option(if (it == 0) ctx.str(R.string.sleep_off) else c.getString(R.string.sleep_minutes, it), "$it") }
    o.choice(ctx.str(R.string.sleep), ctx.str(R.string.sleep_help), options, { "${Sleep.minutes(c)}" }) { Sleep.set(c, it.toInt()) }
    o.link(ctx.str(R.string.screenshot), ctx.str(R.string.screenshot_help), { ctx.str(R.string.screenshot_value) }) { screenshotSoon() }
    lateinit var home: OptionRow
    var armedAt = 0L
    home = o.link(ctx.str(R.string.realhome), ctx.str(R.string.realhome_help), {
        when {
            SystemClock.elapsedRealtime() - armedAt < CONFIRM_MS -> ctx.str(R.string.realhome_confirm)
            RealHome.active(c) -> ctx.str(R.string.realhome_on)
            RealHome.isCouch(c) -> ctx.str(R.string.realhome_already)
            else -> ""
        }
    }) {
        when {
            RealHome.active(c) -> withShell { engine ->
                ctx.str(if (RealHome.give(c, engine)) R.string.realhome_given else R.string.realhome_give_failed)
            }
            RealHome.isCouch(c) -> Unit
            SystemClock.elapsedRealtime() - armedAt >= CONFIRM_MS -> {
                // Two OKs: this one only arms it.
                armedAt = SystemClock.elapsedRealtime()
                home.refresh()
                home.postDelayed({ home.refresh() }, CONFIRM_MS)
            }
            else -> {
                armedAt = 0L
                withShell { engine -> ctx.str(if (RealHome.take(c, engine)) R.string.realhome_done else R.string.realhome_refused) }
            }
        }
    }
}

/** Runs [work] with the shell on a worker and toasts what it returns; refreshes the rows after. */
internal fun GodmodePage.withShell(work: (Engine) -> String) {
    val c = ctx.activity
    thread {
        val message = when (val state = engine.connect()) {
            Engine.State.GRANTED -> runCatching { work(engine) }.getOrElse { it.message ?: ctx.str(R.string.shell_no_ok) }
            Engine.State.DEBUG_OFF -> ctx.str(R.string.debug_off_error)
            Engine.State.BLOCKED -> ctx.str(R.string.shell_blocked_short)
            else -> ctx.str(R.string.shell_no_ok)
        }
        c.runOnUiThread {
            if (c.isFinishing) return@runOnUiThread
            toast(message)
            o.rows().forEach { it.refresh() }
        }
    }
}

/** Ten seconds to get to the screen worth keeping, then a capture into Pictures. */
private fun GodmodePage.screenshotSoon() {
    val app = ctx.activity.applicationContext
    toast(ctx.str(R.string.screenshot_soon))
    Handler(Looper.getMainLooper()).postDelayed({
        thread {
            val name = "Couch-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".png"
            val message = when (Sleep.screenshot(app, name)) {
                Sleep.Shot.TO_PICTURES -> app.getString(R.string.screenshot_saved, name)
                Sleep.Shot.BY_THE_TV -> app.getString(R.string.screenshot_by_tv)
                Sleep.Shot.FAILED -> app.getString(R.string.screenshot_failed)
            }
            Handler(Looper.getMainLooper()).post {
                android.widget.Toast.makeText(app, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }, SCREENSHOT_DELAY_MS)
}

private const val CONFIRM_MS = 4000L
private const val SCREENSHOT_DELAY_MS = 10_000L
