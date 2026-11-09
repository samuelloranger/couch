package cloud.samlo.couch.adb

/**
 * Whether `su` gives this process root, as on a rooted TV where ADB to itself is blocked. Asked
 * when a phone connects or needs a shell (a root manager may show its question then), never at
 * startup. Only a definite answer is remembered: if the question isn't answered in time, `su` is
 * asked again after a minute.
 */
object Root {
    private const val PROBE_MS = 3_000L
    private const val RETRY_MS = 60_000L
    @Volatile private var known: Boolean? = null
    private var retryAt = 0L

    /** True when `id` printed by `su -c id` says uid 0. */
    fun parse(output: String) = Regex("""\buid=0\b""").containsMatchIn(output)

    /** The process arguments that run [command] as root. */
    fun command(command: String) = listOf("su", "-c", command)

    /** The cached answer, without asking: null while nobody has. */
    fun cached(): Boolean? = known

    /** Asks, remembering a definite answer; a timed-out question counts as no for the next minute. */
    @Synchronized fun available(): Boolean {
        known?.let { return it }
        if (System.currentTimeMillis() < retryAt) return false
        val answer = probe()
        if (answer == null) retryAt = System.currentTimeMillis() + RETRY_MS else known = answer
        return answer == true
    }

    /** True for uid 0, false for no `su` or a refusal, null when it didn't answer in time. */
    private fun probe(): Boolean? = try {
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
            null
        }
    } catch (e: Exception) {
        false
    }
}

/**
 * Splits the `PID:<n>` line a wrapped root command prints first from its real output, which passes
 * through byte for byte. Anything that doesn't start that way is all output and no PID.
 */
class PidLine {
    var pid: Int? = null
        private set
    private var head = ByteArray(0)
    private var done = false

    /** Returns the part of [data] that is command output. */
    fun feed(data: ByteArray): ByteArray {
        if (done) return data
        head += data
        val newline = head.indexOf('\n'.code.toByte())
        if (newline < 0) {
            if (head.size <= MAX_LINE && PREFIX.startsWith(String(head, 0, minOf(head.size, PREFIX.length)))) return ByteArray(0)
            return finish(null, 0)
        }
        val n = String(head, 0, newline).trim().removePrefix(PREFIX).toIntOrNull()
        return if (String(head, 0, newline).startsWith(PREFIX) && n != null) finish(n, newline + 1) else finish(null, 0)
    }

    private fun finish(found: Int?, from: Int): ByteArray {
        done = true
        pid = found
        return head.copyOfRange(from, head.size).also { head = ByteArray(0) }
    }

    companion object {
        private const val PREFIX = "PID:"
        private const val MAX_LINE = 24

        /** [command] run by the shell that reports its PID first and then becomes the command. */
        fun wrap(command: String) = "echo $PREFIX\$\$; exec $command"

        /** Asks it to stop, then makes sure. */
        fun kill(pid: Int) = "kill $pid; sleep 0.5; grep -qE 'app_process|screenrecord' /proc/$pid/cmdline && kill -9 $pid"
    }
}

/** Commands run through `su -c`, one process each; output is streamed straight from the process. */
class RootShell : Shell {
    @Volatile private var live: Process? = null
    @Volatile private var closed = false
    @Volatile private var pid: Int? = null

    override fun run(command: String): String = launch(command).let { p ->
        // A su that hangs would block the pipe for ever: end it when the time is up. The watcher
        // returns by itself once the process exits; interrupting it would throw on a bare thread.
        Thread({
            if (!p.waitFor(RUN_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) p.destroyForcibly()
        }, "couch-root-run").apply { isDaemon = true; start() }
        val text = p.inputStream.readBytes().toString(Charsets.UTF_8)
        p.waitFor()
        text
    }

    override fun runLasting(command: String): String = run(command)

    override fun exec(command: String, idleMs: Int, onIdle: () -> Unit, onData: (ByteArray) -> Unit) {
        val p = launch(PidLine.wrap(command))
        live = p
        val marker = PidLine()
        // A thread of its own blocks on the pipe, so a byte is handed over the moment it arrives.
        val chunks = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        val input = p.inputStream
        Thread({
            val buffer = ByteArray(BUFFER)
            try {
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    chunks.put(buffer.copyOf(n))
                }
            } catch (e: java.io.IOException) {
                // The process ended or was closed: the same as the end of its output.
            }
            chunks.put(END)
        }, "couch-root-read").apply { isDaemon = true; start() }
        try {
            while (true) {
                val chunk = chunks.poll(idleMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                if (chunk == null) {
                    onIdle()
                    continue
                }
                if (chunk === END) break
                val out = marker.feed(chunk)
                if (pid == null) marker.pid?.let {
                    pids.add(it)
                    pid = it
                    // Closed before the PID line arrived: nothing else will end it.
                    if (closed) killRoot(it)
                }
                // No flush when the pipe drains: a reader can wake halfway through a large write, and
                // closing the frame there would cut it. The next frame's start, or a quiet gap, ends it.
                if (out.isNotEmpty()) onData(out)
            }
        } catch (e: InterruptedException) {
            throw AdbShell.Failed.Blocked(e)
        } finally {
            // A command that ended by itself has nothing left to kill, and its PID may be someone else's by now.
            val over = !closed && p.waitFor(300, java.util.concurrent.TimeUnit.MILLISECONDS)
            p.destroy()
            pid?.let { if (over) pids.remove(it) else killRoot(it) }
        }
        if (closed) throw AdbShell.Failed.Blocked(null)
    }

    override fun close() {
        closed = true
        live?.destroy()
        pid?.let { killRoot(it) }
    }

    private fun launch(command: String): Process = try {
        ProcessBuilder(Root.command(command)).redirectErrorStream(true).start()
    } catch (e: java.io.IOException) {
        throw AdbShell.Failed.Blocked(e)
    }

    companion object {
        /** Root-side processes this process started and has not seen end: `su` only talks to a daemon, so ending the client leaves them. */
        private val pids = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

        /** Ends the root-side process [pid] if it is one of ours, off the calling thread. */
        private fun killRoot(pid: Int) {
            if (!pids.remove(pid)) return
            Thread({ runCatching { ProcessBuilder(Root.command(PidLine.kill(pid))).redirectErrorStream(true).start().apply { inputStream.readBytes(); waitFor() } } }, "couch-root-kill").apply { isDaemon = true; start() }
        }

        /** Ends every root-side command this process left running (a crashed stream, an earlier source). */
        fun killTracked() = pids.toList().forEach(::killRoot)

        private const val BUFFER = 64 * 1024
        private const val RUN_MS = 30_000L
        private val END = ByteArray(0)
    }
}
