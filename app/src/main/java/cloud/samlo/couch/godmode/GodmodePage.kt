package cloud.samlo.couch.godmode

import android.graphics.Typeface
import android.view.View
import android.widget.Toast
import cloud.samlo.couch.R
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.debloat.DebloatPage
import cloud.samlo.couch.debloat.Engine
import cloud.samlo.couch.debloat.Keepalive
import cloud.samlo.couch.settings.OptionRow
import cloud.samlo.couch.settings.SettingsActivity
import cloud.samlo.couch.settings.SettingsContext
import cloud.samlo.couch.settings.SettingsPage
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.update.UpdateCheck
import kotlin.concurrent.thread

/**
 * The hidden Godmode page, reached by seven OK presses on About and listed in Customize from then
 * on: the shell row first, because the TV settings below wait for the grant it brings. Its accent
 * is phosphor green no matter which accent the user picked; SettingsActivity puts theirs back when
 * the page closes.
 */
class GodmodePage(internal val ctx: SettingsContext) : SettingsPage {
    internal val o = ctx.options
    private var shellRow: OptionRow? = null

    /**
     * One connection for the whole visit: Android asks to allow debugging on the first action that
     * needs it, and the rest of the page reuses that answer. Closed when the page goes away.
     */
    internal val engine by lazy {
        engineStarted = true
        Engine(ctx.activity)
    }
    private var engineStarted = false

    /** The last known state: a key allowed before shows as connected until a try says otherwise. */
    @Volatile private var shellState = if (Keepalive.isGranted(ctx.activity)) Engine.State.GRANTED else Engine.State.OFF

    override fun build() {
        Tokens.applyAccent(Tokens.GODMODE_ACCENT)
        ctx.layout.title.text = ctx.str(R.string.godmode_title)
        ctx.layout.hint.text = ctx.str(R.string.godmode_hint)
        o.header(ctx.str(R.string.godmode_shell_group))
        shellRow = o.link(ctx.str(R.string.shell_row), ctx.str(R.string.shell_row_help), { label() }) { connect() }
        adbCard(shellState)
        tvRows()
        o.header(ctx.str(R.string.godmode_pages_group))
        o.page(ctx.str(R.string.godmode_debloat), ctx.str(R.string.godmode_debloat_help), { debloatLine() }) {
            ctx.activity.startActivity(SettingsActivity.intent(ctx.activity, DebloatPage.page))
        }
        lateinit var cache: OptionRow
        cache = o.link(ctx.str(R.string.tool_cache), ctx.str(R.string.tool_cache_help), {
            ctx.activity.getString(R.string.tool_cache_value, FreeCache.free(ctx.activity))
        }) {
            FreeCache.run(ctx.activity, engine) { cache.refresh() }
        }
        toolRows()
        measureRows()
        o.header(ctx.str(R.string.couch_group))
        o.toggle(ctx.str(R.string.prerelease), ctx.str(R.string.prerelease_help), { ctx.config.preReleases }) {
            ctx.config.preReleases = it
            UpdateCheck.forget(ctx.activity)
        }
        o.link(ctx.str(R.string.godmode_hide), ctx.str(R.string.godmode_hide_help), { hideValue() }) { hide() }
        o.rows().forEach { it.value.typeface = Typeface.MONOSPACE }
        if (!readouts) {
            Readouts.attach(ctx)
            readouts = true
        }
    }

    /** Which build's grant check is current: an older one stops at its next tick. */
    internal var pollGeneration = 0

    /** The readings sit outside the list, so a rebuild of the rows mustn't add them twice. */
    private var readouts = false

    /** What hiding would still have to undo: TV settings, the home, and the HUD. */
    private fun pending() =
        TvSettings.changed(ctx.activity) + (if (RealHome.active(ctx.activity)) 1 else 0) +
            (if (ctx.config.hud != Config.HUD_OFF) 1 else 0)

    private fun hideValue() = if (pending() == 0) "" else ctx.str(R.string.godmode_hide_first)

    /**
     * Takes godmode out of Customize. Anything still running under it goes back first: a hidden
     * page can't undo itself, and the badge that announced it goes with it.
     */
    private fun hide() {
        val c = ctx.activity
        if (RealHome.active(c)) {
            withShell { shell ->
                // Hiding on a failed hand-back would leave Couch the home with nothing to undo it.
                if (!RealHome.give(c, shell)) return@withShell ctx.str(R.string.realhome_give_failed)
                restoreSettings()
                c.runOnUiThread { finishHiding() }
                ctx.str(R.string.putback_done)
            }
            return
        }
        restoreSettings()
        finishHiding()
    }

    /** Without the grant nothing could have been changed, and writing would throw. */
    private fun restoreSettings() {
        if (TvSettings.canWrite(ctx.activity)) TvSettings.restore(ctx.activity)
    }

    private fun finishHiding() {
        ctx.config.godmode = false
        ctx.config.hud = Config.HUD_OFF
        ctx.config.preReleases = false
        ctx.config.save(ctx.files.dir)
        UpdateCheck.forget(ctx.activity)
        Keepalive.forget(ctx.activity)
        ctx.activity.finish()
    }

    /** One OK per try; with "Always allow" ticked the rerun is silent and the row stops asking. */
    internal fun connect(then: (() -> Unit)? = null) {
        shellState = Engine.State.TRYING
        shellRow?.refresh()
        thread {
            val end = engine.connect { s ->
                shellState = s
                ctx.activity.runOnUiThread { shellRow?.refresh() }
            }
            ctx.activity.runOnUiThread {
                shellState = end
                // Every row, not just this one: the TV settings were waiting on this grant.
                o.rows().forEach { it.refresh() }
                if (end == Engine.State.GRANTED) then?.invoke()
                // A refused shell brings up the computer route.
                if (end == Engine.State.BLOCKED || end == Engine.State.DEBUG_OFF) rebuild()
            }
        }
    }

    /** The rows again, for when what's possible changed (a grant landed, the shell was refused). */
    internal fun rebuild() {
        ctx.layout.list.removeAllViews()
        build()
        ctx.layout.list.requestFocus(View.FOCUS_DOWN)
    }

    override fun onDestroy() {
        // Touching the lazy engine would make a key the page never needed.
        if (engineStarted) engine.close()
    }

    internal fun toast(text: String) = Toast.makeText(ctx.activity, text, Toast.LENGTH_LONG).show()

    // The debloat page picks its own entry per TV brand, so the row only points there.
    private fun debloatLine() = if (shellState == Engine.State.BLOCKED) ctx.str(R.string.shell_blocked_short) else ""

    private fun label() = ctx.str(
        when (shellState) {
            Engine.State.OFF -> R.string.shell_off
            Engine.State.TRYING -> R.string.shell_trying
            Engine.State.NEEDS_OK -> R.string.shell_needs_ok
            Engine.State.GRANTED -> R.string.shell_granted
            Engine.State.BLOCKED -> R.string.shell_blocked
            Engine.State.DEBUG_OFF -> R.string.shell_debug_off
        },
    )

    companion object {
        const val page = "godmode"
    }
}
