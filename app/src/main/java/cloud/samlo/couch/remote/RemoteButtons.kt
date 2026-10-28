package cloud.samlo.couch.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import cloud.samlo.couch.R
import cloud.samlo.couch.adb.AdbShell
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.Prefs
import cloud.samlo.couch.data.UserFiles
import java.util.concurrent.Executors

/**
 * The remote's Input and Settings buttons opening Couch's panels instead of the TV's menus. The
 * relay (`src/main/native/relay.c`), started over the TV's own ADB, holds those two keys back and
 * reports them here; every other key reaches Android untouched. Off unless turned on in
 * Customize, and only while Couch's accessibility service runs, since its panels are drawn by it.
 */
class RemoteButtons(private val context: Context) {
    enum class State { OFF, STARTING, RUNNING, NO_DEBUGGING, NOT_ALLOWED, FAILED }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val inputs = OverlayPanel(context, context.getString(R.string.remote_input_hint))
    // Settings opens on the right, like the TV's own panel sits apart from the inputs.
    private val quick = OverlayPanel(context, context.getString(R.string.remote_quick_hint), end = true)
    private val bluetooth = BluetoothAudio(context)
    private val tv = TvQuick(context, bluetooth::connectedName) {
        prepareQuick()
        quick.refreshRows()
    }
    @Volatile private var config = Config()
    @Volatile private var link: RelayLink? = null
    @Volatile private var stopped = false
    private var learner: ((Int?) -> Unit)? = null
    private var failures = 0
    private val retry = Runnable { connect(prompt = false) }

    /** Reads the settings again and starts or stops the relay to match. [prompt] may ask to allow debugging. */
    fun reload(prompt: Boolean = false) {
        config = Config.load(UserFiles(context).dir)
        main.removeCallbacks(retry)
        if (!config.remoteButtons) {
            link?.close()
            state = State.OFF
            return
        }
        bluetooth.start()
        prepareQuick()
        tv.refresh()
        loadInputs()
        val live = link
        if (live != null) live.send(RelayProtocol.keys(wanted())) else connect(prompt)
    }

    /** Waits for the next button pressed on the remote; [done] gets its code, or null on Back. */
    fun learn(done: (Int?) -> Unit) {
        val live = link ?: return done(null)
        learner = done
        live.send(RelayProtocol.learn(true))
    }

    /** Asks for the PIN the TV shows while pairing, above the TV's own PIN window. */
    fun askPin(done: (String?) -> Unit) = PinPad.show(context, done)

    fun stop() {
        stopped = true
        main.removeCallbacks(retry)
        link?.close()
        inputs.release()
        quick.release()
        tv.release()
        bluetooth.release()
        worker.shutdownNow()
        state = State.OFF
    }

    private fun connect(prompt: Boolean) {
        if (stopped) return
        state = State.STARTING
        worker.execute {
            val created = RelayLink({ line -> RelayProtocol.parse(line)?.let { main.post { onEvent(it) } } }) { main.post(::onLost) }
            val shell = AdbShell.create(context, prompt)
            val result = try {
                val out = shell.runLasting(RelayProtocol.start(context.applicationInfo.nativeLibraryDir, created.port, created.token))
                // Quick settings show the Bluetooth audio devices; Android 12 asks for this to list them.
                if (context.checkSelfPermission(BluetoothAudio.CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    runCatching { shell.run("pm grant ${context.packageName} ${BluetoothAudio.CONNECT}") }
                }
                if (RelayProtocol.STARTED !in out) {
                    Log.w("Couch", "Remote relay didn't start: ${out.trim()}")
                    State.FAILED
                } else if (created.await(AWAIT_MS)) State.RUNNING else State.FAILED
            } catch (e: AdbShell.Failed.Off) {
                State.NO_DEBUGGING
            } catch (e: AdbShell.Failed.NoOk) {
                State.NOT_ALLOWED
            } catch (e: AdbShell.Failed) {
                Log.w("Couch", "Remote relay: ADB failed", e)
                State.FAILED
            } finally {
                shell.close()
            }
            main.post {
                if (result != State.RUNNING) created.close() else if (stopped) created.close() else {
                    bluetooth.start()
                    link = created
                    failures = 0
                    created.send(RelayProtocol.keys(wanted()))
                }
                state = result
                if (result == State.FAILED) scheduleRetry()
            }
        }
    }

    /** The relay ended (a crash, or killed): start it again, more slowly each time it keeps failing. */
    private fun onLost() {
        link = null
        learner?.invoke(null)
        learner = null
        if (stopped || !config.remoteButtons) return
        state = State.FAILED
        scheduleRetry()
    }

    private fun scheduleRetry() {
        val delay = RETRY_MS.getOrElse(failures) { RETRY_MS.last() }
        failures++
        main.removeCallbacks(retry)
        main.postDelayed(retry, delay)
    }

    private fun onEvent(event: RelayProtocol.Event) {
        when (event) {
            is RelayProtocol.Event.Seen -> {
                if (event.code in RelayProtocol.RESERVED && event.code != RelayProtocol.BACK) return
                link?.send(RelayProtocol.learn(false))
                val done = learner
                learner = null
                done?.invoke(event.code.takeIf { it != RelayProtocol.BACK })
            }
            is RelayProtocol.Event.Key -> if (event.down) pressed(event.code)
        }
    }

    private fun pressed(code: Int) {
        when (code) {
            config.remoteInputKey -> {
                quick.close()
                // A second press steps to the next input, as the TV's own list does.
                inputs.show(if (inputs.isOpen) nextInput() else Prefs.of(context).getString(LAST_INPUT, null))
            }
            config.remoteSettingsKey -> {
                inputs.close()
                // A second press hands over to the TV's own quick settings.
                if (quick.isOpen) {
                    quick.close()
                    tvMenu()
                } else {
                    prepareQuick()
                    quick.show(null)
                    // Picture mode may have changed on the TV's own menu since: read it again.
                    tv.refresh()
                }
            }
        }
    }

    private fun nextInput(): String? {
        val keys = inputs.inputKeys
        val at = keys.indexOf(inputs.current)
        return keys.getOrNull(at + 1) ?: keys.firstOrNull()
    }

    private fun prepareQuick() {
        val code = config.remoteSettingsKey
        val audio = bluetooth.items(context.getString(R.string.quick_section_bluetooth))
        quick.prepare(QuickItems.build(context, if (code > 0) ::tvMenu else null, tv.items() + audio))
    }

    /** Replays the Settings button to the TV, so its own quick settings open. */
    private fun tvMenu() {
        val code = config.remoteSettingsKey
        if (code > 0) link?.send(RelayProtocol.emit(code))
    }

    private fun wanted() = listOf(config.remoteInputKey, config.remoteSettingsKey)

    /** Apps installed or hidden, inputs renamed: read the list again, for the next opening. */
    private fun loadInputs() {
        if (stopped) return
        worker.execute { InputItems.load(context).let { main.post { inputs.prepare(it) } } }
    }

    init {
        inputs.onChosen = { Prefs.of(context).edit().putString(LAST_INPUT, it).apply() }
        inputs.onClosed = ::loadInputs
    }

    companion object {
        private const val LAST_INPUT = "inputs:last"
        private const val AWAIT_MS = 8_000
        private val RETRY_MS = listOf(3_000L, 15_000L, 60_000L, 300_000L)

        /** What the settings page shows; set on the main thread. */
        @Volatile var state = State.OFF
            private set
    }
}
