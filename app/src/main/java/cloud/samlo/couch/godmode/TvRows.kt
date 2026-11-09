package cloud.samlo.couch.godmode

import android.app.ActivityManager
import android.text.format.Formatter
import cloud.samlo.couch.R
import cloud.samlo.couch.adb.AdbShell
import cloud.samlo.couch.debloat.Engine
import cloud.samlo.couch.settings.Option
import kotlin.concurrent.thread

/**
 * The TV's own settings: put back what an earlier Godmode changed, and closing background apps,
 * now or once they sit idle. Writing needs the shell's grant; without it, a row connects first and
 * then applies the change.
 */
internal fun GodmodePage.tvRows() {
    val c = ctx.activity
    o.header(ctx.str(R.string.tv_group))
    o.link(ctx.str(R.string.putback), ctx.str(R.string.putback_help), { putBackValue() }) {
        writing {
            TvSettings.restore(c)
            if (RealHome.active(c)) {
                withShell { engine -> ctx.str(if (RealHome.give(c, engine)) R.string.putback_done else R.string.realhome_give_failed) }
            } else {
                toast(ctx.str(R.string.putback_done))
            }
        }
    }
    o.link(ctx.str(R.string.kill_bg), ctx.str(R.string.kill_bg_help)) { closeBackground() }
    val limits = listOf(0, 30, 60, 120, 240).map { Option(if (it == 0) ctx.str(R.string.idle_close_off) else c.getString(R.string.idle_close_minutes, it), "$it") }
    o.choice(ctx.str(R.string.idle_close), ctx.str(R.string.idle_close_help), limits, { "${IdleClose.minutes(c)}" }) { IdleClose.set(c, it.toInt()) }
}

private fun GodmodePage.putBackValue(): String {
    val c = ctx.activity
    if (!TvSettings.canWrite(c)) return ctx.str(R.string.needs_shell)
    val n = TvSettings.changed(c) + if (RealHome.active(c)) 1 else 0
    return if (n == 0) ctx.str(R.string.putback_none) else c.resources.getQuantityString(R.plurals.putback_value, n, n)
}

/** Runs [change] once Couch may write the TV's settings, connecting first if it can't yet. */
private fun GodmodePage.writing(change: () -> Unit) {
    if (TvSettings.canWrite(ctx.activity)) {
        change()
        o.rows().forEach { it.refresh() }
    } else {
        toast(ctx.str(R.string.needs_shell_toast))
        connect {
            change()
            o.rows().forEach { it.refresh() }
        }
    }
}

/** `am kill-all` closes what Android would close anyway under memory pressure, just now. */
private fun GodmodePage.closeBackground() {
    val c = ctx.activity
    thread {
        val state = try {
            engine.connect()
        } catch (e: Exception) {
            Engine.State.BLOCKED
        }
        val message = if (state == Engine.State.GRANTED) {
            try {
                val before = available()
                engine.killBackground()
                Thread.sleep(SETTLE_MS)
                val freed = available() - before
                if (freed < MEANINGFUL) ctx.str(R.string.kill_bg_none)
                else c.getString(R.string.kill_bg_done, Formatter.formatShortFileSize(c, freed))
            } catch (e: AdbShell.Failed) {
                // The shell dropped mid-command: this thread is the launcher's process.
                ctx.str(R.string.shell_no_ok)
            }
        } else {
            ctx.str(if (state == Engine.State.DEBUG_OFF) R.string.debug_off_error else R.string.shell_no_ok)
        }
        c.runOnUiThread { if (!c.isFinishing) toast(message) }
    }
}

private fun GodmodePage.available(): Long {
    val mem = ActivityManager.MemoryInfo()
    ctx.activity.getSystemService(ActivityManager::class.java)?.getMemoryInfo(mem)
    return mem.availMem
}

/** Android counts the freed memory back a moment after the processes go. */
private const val SETTLE_MS = 1500L
private const val MEANINGFUL = 4L * 1024 * 1024
