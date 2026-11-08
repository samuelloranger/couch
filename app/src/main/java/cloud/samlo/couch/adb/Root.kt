package cloud.samlo.couch.adb

/**
 * Whether `su` gives this process root, as on a rooted TV where ADB to itself is blocked. Asked at
 * most once per process and only when a phone feature needs a shell (a root manager may show its
 * question then), never at startup.
 */
object Root {
    private const val PROBE_MS = 3_000L
    @Volatile private var known: Boolean? = null

    /** True when `id` printed by `su -c id` says uid 0. */
    fun parse(output: String) = Regex("""\buid=0\b""").containsMatchIn(output)

    /** The process arguments that run [command] as root. */
    fun command(command: String) = listOf("su", "-c", command)

    /** The cached answer, without asking: null while nobody has. */
    fun cached(): Boolean? = known

    /** Asks once, then remembers. */
    @Synchronized fun available(): Boolean = known ?: probe().also { known = it }

    private fun probe(): Boolean = try {
        val process = ProcessBuilder(command("id")).redirectErrorStream(true).start()
        val out = StringBuilder()
        val reader = Thread({ runCatching { process.inputStream.bufferedReader().forEachLine { if (out.length < 4096) out.append(it).append('\n') } } }, "couch-root-probe")
        reader.isDaemon = true
        reader.start()
        if (process.waitFor(PROBE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            reader.join(500)
            parse(out.toString())
        } else {
            process.destroyForcibly()
            false
        }
    } catch (e: Exception) {
        false
    }
}

/** Commands run through `su -c`, one process each; output is streamed straight from the process. */
class RootShell : Shell {
    @Volatile private var live: Process? = null
    @Volatile private var closed = false

    override fun run(command: String): String = launch(command).let { p ->
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor()
        text
    }

    override fun runLasting(command: String): String = run(command)

    override fun exec(command: String, idleMs: Int, onIdle: () -> Unit, onData: (ByteArray) -> Unit) {
        val p = launch(command)
        live = p
        val input = p.inputStream
        val buffer = ByteArray(BUFFER)
        var quiet = 0
        try {
            while (true) {
                val ready = input.available()
                if (ready > 0) {
                    quiet = 0
                    val n = input.read(buffer, 0, minOf(ready, buffer.size))
                    if (n < 0) break
                    onData(buffer.copyOf(n))
                } else if (!p.isAlive) {
                    break
                } else {
                    Thread.sleep(POLL_MS)
                    quiet += POLL_MS.toInt()
                    if (quiet >= idleMs) {
                        quiet = 0
                        onIdle()
                    }
                }
            }
        } catch (e: java.io.IOException) {
            throw AdbShell.Failed.Blocked(e)
        } finally {
            p.destroy()
        }
        if (closed) throw AdbShell.Failed.Blocked(null)
    }

    override fun close() {
        closed = true
        live?.destroy()
    }

    private fun launch(command: String): Process = try {
        ProcessBuilder(Root.command(command)).redirectErrorStream(true).start()
    } catch (e: java.io.IOException) {
        throw AdbShell.Failed.Blocked(e)
    }

    private companion object {
        const val BUFFER = 64 * 1024
        const val POLL_MS = 10L
    }
}
