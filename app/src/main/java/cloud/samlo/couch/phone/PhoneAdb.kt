package cloud.samlo.couch.phone

import android.content.Context
import android.provider.Settings
import cloud.samlo.couch.adb.AdbShell
import cloud.samlo.couch.adb.Root
import cloud.samlo.couch.adb.RootShell
import cloud.samlo.couch.adb.Shell
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the phone app knows about the TV's own ADB, shared by keys, text and video: the state it
 * shows ("Allow debugging on the TV"), and the one-time right to put Android's question on the
 * TV's screen from a background request. Settings, apps and the like never touch it.
 */
object PhoneAdb {
    /** The values `state.adb` takes on the wire. */
    enum class State(val wire: String) { UNKNOWN("unknown"), READY("ready"), ROOT("root"), DIALOG("dialog"), OFF("off"), BLOCKED("blocked") }

    @Volatile var state = State.UNKNOWN
        private set

    /** Called on a worker when [state] changes. */
    @Volatile var onChange: (() -> Unit)? = null

    private val asked = AtomicBoolean()

    /** True once per process: the request that gets it may put the "Allow debugging?" question on screen. */
    fun claimPrompt(): Boolean = asked.compareAndSet(false, true)

    /** Whether Developer options has debugging on; without it nothing listens, whatever the state says. */
    fun enabled(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1

    /** Whether a shell works, by ADB or as root. */
    val usable get() = state == State.READY || state == State.ROOT

    /**
     * A shell for keys, text and video, or null when there is no way to one. Root, where `su` gives
     * it, comes first and asks nothing of the user (a root manager may ask once); [probeRoot] false
     * only uses root already found. Otherwise ADB: with [prompt] off it fails instead of asking,
     * and a request that may ask does so at most once per process. Connects on first use.
     */
    fun create(context: Context, prompt: Boolean, probeRoot: Boolean = true): Shell? {
        if (if (probeRoot) Root.available() else Root.cached() == true) return RootShell()
        if (!enabled(context)) return null
        val ask = prompt && !usable && claimPrompt()
        return AdbShell.create(context, ask) { phase ->
            when (phase) {
                AdbShell.Phase.DIALOG -> set(State.DIALOG)
                AdbShell.Phase.READY -> set(State.READY)
                else -> Unit
            }
        }
    }

    fun ready(shell: Shell) = set(if (shell is RootShell) State.ROOT else State.READY)

    /** Records why a connection failed. */
    fun failed(e: AdbShell.Failed) = set(
        when (e) {
            is AdbShell.Failed.Off -> State.OFF
            is AdbShell.Failed.NoOk -> State.DIALOG
            is AdbShell.Failed.Blocked -> State.BLOCKED
        },
    )

    private fun set(next: State) {
        if (state == next) return
        state = next
        onChange?.invoke()
    }

    /** What the phone shows when a key, text or the picture can't go through. */
    fun message(): String = when (state) {
        State.DIALOG -> "Allow debugging on the TV."
        State.OFF -> "Turn on debugging on the TV."
        State.BLOCKED -> "This TV blocks debugging."
        else -> "Debugging isn't ready."
    }
}
