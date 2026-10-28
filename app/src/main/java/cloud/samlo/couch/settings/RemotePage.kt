package cloud.samlo.couch.settings

import android.app.AlertDialog
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import cloud.samlo.couch.HomeAccessibilityService
import cloud.samlo.couch.R
import cloud.samlo.couch.adb.AdbShell
import cloud.samlo.couch.bravia.Bravia
import cloud.samlo.couch.remote.RemoteButtons
import kotlin.concurrent.thread

/**
 * Remote buttons: the switch that lets Couch's panels answer the remote's Input and Settings
 * buttons, each button set up by pressing it, and what the relay is doing. Off by default.
 */
class RemotePage(private val ctx: SettingsContext) : SettingsPage {
    private val o = ctx.options
    private val config get() = ctx.config
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            o.rows().forEach { it.refresh() }
            main.postDelayed(this, TICK_MS)
        }
    }
    private var learning: AlertDialog? = null
    private val bravia = Bravia(ctx.activity)
    /** Null until the TV has been asked whether it has Sony's control API. */
    @Volatile private var hasApi: Boolean? = null

    override fun build() {
        ctx.layout.title.text = ctx.str(R.string.remote_title)
        ctx.layout.hint.text = ctx.str(R.string.remote_hint)
        o.header(ctx.str(R.string.remote_group))
        val toggle = o.toggle(ctx.str(R.string.remote_enable), ctx.str(R.string.remote_enable_help), { config.remoteButtons }) { config.remoteButtons = it }
        // The toggle saves after setting; act once the file says so.
        toggle.setOnClickListener {
            config.remoteButtons = !config.remoteButtons
            o.save(toggle)
            apply(prompt = true)
        }
        o.link(ctx.str(R.string.remote_status), ctx.str(R.string.remote_status_help), { status() }) { apply(prompt = true) }
        o.header(ctx.str(R.string.remote_buttons))
        button(R.string.remote_input, R.string.remote_input_help, { config.remoteInputKey }) { config.remoteInputKey = it }
        button(R.string.remote_settings, R.string.remote_settings_help, { config.remoteSettingsKey }) { config.remoteSettingsKey = it }
        o.header(ctx.str(R.string.remote_tv_group))
        o.link(ctx.str(R.string.remote_tv_pair), ctx.str(R.string.remote_tv_pair_help), { pairing() }) { pair() }
        thread(name = "couch-bravia-check") { hasApi = bravia.available() }
        main.post(tick)
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        learning?.dismiss()
        // Leaving mid-setup: the relay must stop holding back every key.
        HomeAccessibilityService.remote()?.cancelLearn()
    }

    private fun button(title: Int, help: Int, get: () -> Int, set: (Int) -> Unit) {
        val row = o.link(ctx.str(title), ctx.str(help), { get().let { if (it > 0) ctx.activity.getString(R.string.remote_key, it) else ctx.str(R.string.remote_key_unset) } }) {}
        row.setOnClickListener {
            val remote = HomeAccessibilityService.remote()
            if (remote == null || RemoteButtons.state != RemoteButtons.State.RUNNING) {
                toast(R.string.remote_learn_unavailable)
                return@setOnClickListener
            }
            learning = AlertDialog.Builder(ctx.activity, Dialogs.THEME)
                .setTitle(title)
                .setMessage(R.string.remote_learn_message)
                .setCancelable(false)
                .show()
            remote.learn { code ->
                learning?.dismiss()
                learning = null
                if (code == null) return@learn
                set(code)
                o.save(row)
                remote.reload()
            }
        }
    }

    private fun pairing(): String = ctx.str(
        when (hasApi) {
            null -> R.string.remote_state_starting
            false -> R.string.remote_tv_unavailable
            true -> if (bravia.paired) R.string.remote_tv_paired else R.string.remote_tv_unpaired
        },
    )

    /**
     * The TV shows a PIN; entered here, it pairs Couch for good (the sign-in renews itself). The
     * TV's PIN window covers the screen and takes the remote, so the PIN is asked for on Couch's
     * overlay above it, which needs Couch's accessibility service; without it, after the window.
     */
    private fun pair() {
        if (hasApi != true) return
        val activity = ctx.activity
        thread(name = "couch-bravia-pair") {
            // Already paired: OK pairs again from scratch, which is how a stuck pairing gets fixed.
            bravia.forget()
            val asked = runCatching { bravia.startPairing() }.isSuccess
            activity.runOnUiThread {
                if (!asked) return@runOnUiThread toast(R.string.remote_tv_pair_failed)
                val remote = HomeAccessibilityService.remote()
                if (remote != null) {
                    remote.askPin { pin -> if (pin != null) finishPairing(pin) }
                } else {
                    Dialogs.text(activity, ctx.str(R.string.remote_tv_pin_title), "", ctx.str(R.string.remote_tv_pin_hint)) { pin ->
                        if (pin.isNotEmpty()) finishPairing(pin)
                    }
                }
            }
        }
    }

    private fun finishPairing(pin: String) {
        val activity = ctx.activity
        thread(name = "couch-bravia-pin") {
            val ok = runCatching { bravia.finishPairing(pin) }.isSuccess
            activity.runOnUiThread {
                toast(if (ok) R.string.remote_tv_pair_done else R.string.remote_tv_pair_failed)
                if (ok) HomeAccessibilityService.remote()?.reload()
                o.rows().forEach { it.refresh() }
            }
        }
    }

    /** Starts or stops the relay to match the switch; turns Couch's service on first if it's off. */
    private fun apply(prompt: Boolean) {
        val remote = HomeAccessibilityService.remote()
        if (remote != null) return remote.reload(prompt)
        if (!config.remoteButtons) return
        // The panels are drawn by Couch's accessibility service: turn it on over ADB.
        val me = ComponentName(ctx.activity, HomeAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(ctx.activity.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val services = (enabled.split(':').filter { it.isNotBlank() } + me).distinct().joinToString(":")
        HomeAccessibilityService.wanted(ctx.activity, true)
        val activity = ctx.activity
        thread(name = "couch-remote-enable") {
            val shell = AdbShell.create(activity, prompt = true)
            val ok = runCatching {
                shell.run("settings put secure enabled_accessibility_services '$services'; settings put secure accessibility_enabled 1")
            }.isSuccess
            shell.close()
            if (!ok) activity.runOnUiThread { toast(R.string.remote_needs_debugging) }
        }
    }

    private fun status(): String = ctx.str(
        when {
            !config.remoteButtons -> R.string.remote_state_off
            HomeAccessibilityService.remote() == null -> R.string.remote_state_service
            else -> when (RemoteButtons.state) {
                RemoteButtons.State.OFF, RemoteButtons.State.STARTING -> R.string.remote_state_starting
                RemoteButtons.State.RUNNING -> R.string.remote_state_running
                RemoteButtons.State.NO_DEBUGGING -> R.string.remote_state_no_debugging
                RemoteButtons.State.NOT_ALLOWED -> R.string.remote_state_not_allowed
                RemoteButtons.State.FAILED -> R.string.remote_state_failed
            }
        },
    )

    private fun toast(text: Int) = Toast.makeText(ctx.activity, text, Toast.LENGTH_LONG).show()

    companion object {
        const val PAGE = "remote"
        private const val TICK_MS = 1_000L
    }
}
