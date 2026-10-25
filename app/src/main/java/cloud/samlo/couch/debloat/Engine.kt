package cloud.samlo.couch.debloat

import android.content.Context
import android.os.SystemClock
import cloud.samlo.couch.adb.AdbShell
import kotlin.concurrent.thread

/**
 * The TV's debugging shell, flavoured for debloat: [AdbShell]'s handshake distilled into the states
 * the screens show, plus the commands removals are made of. Android never lets an app remove
 * another app, so everything here borrows the TV's own `shell` right. Blocking: call from a worker.
 * Without a [list] it connects, measures and clears caches, but refuses to remove anything.
 *
 * One engine holds one connection, so a piece of work that takes several commands can only put
 * Android's "Allow USB debugging?" question on screen once. [close] it when the work is done.
 */
class Engine(private val context: Context, private val list: DebloatList? = null) {
    /** What the connection row shows. */
    enum class State { OFF, TRYING, NEEDS_OK, GRANTED, BLOCKED, DEBUG_OFF }

    /** A removal or restore the TV refused; [output] is what `pm` said. */
    class Refused(val pkg: String, val output: String) : Exception("$pkg: ${output.trim().take(120)}")

    @Volatile private var adb: AdbShell? = null

    /**
     * Lets the connection go; the next [connect] opens a new one. Takes no lock and closes off the
     * calling thread, so a page can leave while a command is still running: that command fails
     * as its socket goes.
     */
    fun close() {
        val shell = adb ?: return
        adb = null
        thread { shell.close() }
    }

    /**
     * Authorizes, reporting each turn to [onEach]. A connection this engine already holds is used
     * again, so one answer to Android's question covers every action of a visit; only when there
     * is none, or it has died, does a new one go up. One retry after an unanswered question:
     * Android drops a prompt on its own when the TV is just waking. Ends [State.GRANTED],
     * [State.BLOCKED] (the TV refuses apps; a computer is the way), [State.DEBUG_OFF] or
     * [State.NEEDS_OK].
     */
    fun connect(onEach: (State) -> Unit = {}): State = synchronized(this) {
        var reported = State.OFF
        fun tell(state: State): State {
            if (state != reported) {
                reported = state
                onEach(state)
            }
            return state
        }
        // The connection from an earlier action, if it is still good.
        adb?.let { live ->
            try {
                if (granted(live)) return tell(State.GRANTED)
            } catch (e: Exception) {
                // Died while nobody was looking; a fresh one goes up below.
            }
            live.close()
            adb = null
        }
        repeat(ATTEMPTS) { attempt ->
            try {
                val shell = AdbShell.create(context) { phase ->
                    when (phase) {
                        AdbShell.Phase.TRYING -> tell(State.TRYING)
                        AdbShell.Phase.DIALOG -> tell(State.NEEDS_OK)
                        AdbShell.Phase.READY -> Unit
                    }
                }
                adb = shell
                if (!granted(shell)) return tell(State.BLOCKED)
                Keepalive.granted(context)
                return tell(State.GRANTED)
            } catch (e: AdbShell.Failed.Blocked) {
                return tell(State.BLOCKED)
            } catch (e: AdbShell.Failed.Off) {
                return tell(State.DEBUG_OFF)
            } catch (e: AdbShell.Failed.NoOk) {
                tell(State.NEEDS_OK)
                if (attempt < ATTEMPTS - 1) SystemClock.sleep(RETRY_MS)
            } catch (e: Exception) {
                // A key file that can't be read or written: no shell, and callers run on bare threads.
                return tell(State.BLOCKED)
            }
        }
        return reported
    }

    /**
     * Reconnects without ever putting the question on screen: true when it worked, false when the
     * key isn't allowed any more, null when the shell couldn't be reached at all.
     */
    fun touch(): Boolean? {
        val shell = try {
            AdbShell.create(context, prompt = false)
        } catch (e: Exception) {
            // Generating or reading the key failed; this runs on a bare thread.
            return null
        }
        return try {
            shell.run("true")
            true
        } catch (e: AdbShell.Failed.NoOk) {
            false
        } catch (e: Exception) {
            // Runs on the keepalive's bare thread: anything else just means "couldn't reach it now".
            null
        } finally {
            shell.close()
        }
    }

    /**
     * Memory used by those of [pkgs] that are running, read silently; null when the shell can't be
     * had without asking. A full `dumpsys meminfo` takes seconds on a TV, so this lists processes
     * first and asks only about the running ones.
     */
    fun memory(pkgs: Collection<String>): Map<String, Int>? {
        val shell = try {
            AdbShell.create(context, prompt = false)
        } catch (e: Exception) {
            return null
        }
        return try {
            val names = shell.run("ps -A -o NAME").lines().map(String::trim)
            val running = pkgs.filter { pkg -> SAFE_NAME.matches(pkg) && names.any { it == pkg || it.startsWith("$pkg:") } }
            if (running.isEmpty()) emptyMap()
            else Impacts.parseMemory(shell.run(running.joinToString("; ") { "dumpsys meminfo -s $it" }))
        } catch (e: AdbShell.Failed) {
            null
        } finally {
            shell.close()
        }
    }

    /**
     * Asks who the shell runs as and grants Couch the settings right in one go: a second command
     * would be a second stream, and on a TV that forgot the key, a second question on screen.
     * Root adbd (common on cheap boxes) answers uid=0 and can grant just as well.
     */
    private fun granted(shell: AdbShell): Boolean {
        val out = shell.run("id; pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS")
        return out.contains("uid=2000(shell)") || out.contains("uid=0(root)")
    }

    /** One shell command, output as text; call after [connect] granted the shell. */
    fun run(command: String): String = synchronized(this) {
        (adb ?: throw AdbShell.Failed.Off(null)).run(command)
    }

    /** Closes background apps, as Android would under memory pressure; call after [connect]. */
    fun killBackground() {
        run("am kill-all")
    }

    /** Clears every app's cache (apps rebuild them as they go); call after [connect]. */
    fun trimCaches() {
        run("pm trim-caches 999G")
    }

    /**
     * `pm uninstall -k --user 0` keeps the APK, so `install-existing` can bring the app back. Only
     * packages this brand's list names, and never a protected one; done only once the package
     * manager agrees the app is gone.
     */
    fun remove(pkg: String) {
        require(SAFE_NAME.matches(pkg) && list?.entry(pkg) != null) { "$pkg is not on Couch's list" }
        val out = run("pm uninstall -k --user 0 $pkg")
        if (!out.contains("Success") || Packages.installed(context, pkg)) throw Refused(pkg, out)
    }

    /** Undoes one removal; done only once the package manager sees the app again. */
    fun restore(pkg: String) {
        require(SAFE_NAME.matches(pkg)) { "$pkg is not a package name" }
        val out = run("cmd package install-existing $pkg")
        if (!Packages.installed(context, pkg)) throw Refused(pkg, out)
    }

    /** Restarts the TV, so the check after a batch also catches apps the firmware brings back. */
    fun reboot() {
        run("svc power reboot || reboot")
    }

    companion object {
        private const val ATTEMPTS = 2
        private const val RETRY_MS = 4_000L
        /** Package names only, so nothing else can reach the shell command. */
        private val SAFE_NAME = Regex("[A-Za-z0-9_.]+")
    }
}
