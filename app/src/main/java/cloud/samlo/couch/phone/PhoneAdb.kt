package cloud.samlo.couch.phone

import android.content.Context
import android.provider.Settings
import cloud.samlo.couch.adb.AdbShell
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What the phone app knows about the TV's own ADB, shared by keys, text and video: the state it
 * shows ("Allow debugging on the TV"), and the one-time right to put Android's question on the
 * TV's screen from a background request. Settings, apps and the like never touch it.
 */
object PhoneAdb {
    /** The values `state.adb` takes on the wire. */
    enum class State(val wire: String) { UNKNOWN("unknown"), READY("ready"), DIALOG("dialog"), OFF("off"), BLOCKED("blocked") }

    @Volatile var state = State.UNKNOWN
        private set

    /** Called on a worker when [state] changes. */
    @Volatile var onChange: (() -> Unit)? = null

    private val asked = AtomicBoolean()

    /** True once per process: the request that gets it may put the "Allow debugging?" question on screen. */
    fun claimPrompt(): Boolean = asked.compareAndSet(false, true)

    /** Whether Developer options has debugging on; without it nothing listens, whatever the state says. */
    fun enabled(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1

    /** A shell that, when [prompt] is off, fails instead of asking. Connects on first use. */
    fun create(context: Context, prompt: Boolean): AdbShell = AdbShell.create(context, prompt) { phase ->
        when (phase) {
            AdbShell.Phase.DIALOG -> set(State.DIALOG)
            AdbShell.Phase.READY -> set(State.READY)
            else -> Unit
        }
    }

    fun ready() = set(State.READY)

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
