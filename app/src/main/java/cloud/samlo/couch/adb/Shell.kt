package cloud.samlo.couch.adb

/** What the phone features need of a shell on the TV, whether it is ADB to the TV's own adbd or root. */
interface Shell {
    /** Runs one command and returns its output. */
    fun run(command: String): String

    /** Like [run], for a command that leaves something running in the background (the relay). */
    fun runLasting(command: String): String

    /** Streams a command's output as it arrives; [onIdle] fires when [idleMs] pass with none. Ends with the command. */
    fun exec(command: String, idleMs: Int, onIdle: () -> Unit, onData: (ByteArray) -> Unit)

    /** Ends the connection or process; a [exec] in progress on another thread then fails. */
    fun close()
}
