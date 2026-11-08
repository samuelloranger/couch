package cloud.samlo.couch.phone

import android.content.Context
import cloud.samlo.couch.HomeAccessibilityService
import cloud.samlo.couch.adb.AdbShell
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.UserFiles
import cloud.samlo.couch.remote.RelayLink
import cloud.samlo.couch.remote.RelayProtocol
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Keys and text from a phone. A key goes through the native relay's `emit`/`press` when one runs
 * (the Remote buttons' own, else one started here with no keys held back), which is fast; until
 * one is up, or for a key the relay can't send, it goes as `input keyevent` over one persistent ADB
 * connection. All of it runs on one worker, in the order the phone pressed, and a flood past
 * [QUEUE] waiting presses is dropped rather than queued for minutes.
 */
class Control(private val context: Context) {
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(QUEUE),
        { r -> Thread(r, "couch-phone-control").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy(),
    )
    private var shell: AdbShell? = null
    private var own: RelayLink? = null
    private var relayRetryAt = 0L

    /** [reply] gets null when the key went out, else the message for the phone. */
    fun key(key: Keys.Key, action: String, reply: (String?) -> Unit) = worker.execute {
        val code = key.linux
        val line = when {
            code == null -> null
            action == "down" -> RelayProtocol.press(code, true)
            action == "up" -> RelayProtocol.press(code, false)
            else -> RelayProtocol.emit(code)
        }
        if (line != null && relay(line)) return@execute reply(null)
        // The relay can't carry this one (yet): a key-up has nothing to release, a key-down taps.
        if (action == "up") return@execute reply(null)
        val sent = shell { it.run("input keyevent ${key.android}") }
        reply(if (sent != null) null else PhoneAdb.message())
        if (sent != null && line != null) ensureRelay()
    }

    fun text(text: String, reply: (String?) -> Unit) = worker.execute {
        val command = Keys.textCommand(text) ?: return@execute reply(null)
        reply(if (shell { it.run(command) } != null) null else PhoneAdb.message())
    }

    /** Learns whether ADB is usable, without ever asking the TV to show its question. */
    fun probe() = worker.execute {
        if (PhoneAdb.state == PhoneAdb.State.READY || !PhoneAdb.enabled(context)) {
            if (!PhoneAdb.enabled(context)) shell = null
            return@execute
        }
        shell(allowPrompt = false) { it.run("true") }
    }

    /** The last phone has gone: the connection and any relay of ours are let go. */
    fun release() = worker.execute {
        shell?.close()
        shell = null
        own?.close()
        own = null
    }

    private fun relay(line: String): Boolean {
        if (HomeAccessibilityService.remote()?.relay(line) == true) return true
        val link = own ?: return false
        link.send(line)
        return true
    }

    /**
     * Starts a relay that holds no key back, only to inject keys. Not while the Remote buttons are
     * on: they run the relay, and starting a second would stop it and set the two fighting.
     */
    private fun ensureRelay() {
        if (own != null || System.currentTimeMillis() < relayRetryAt) return
        if (Config.load(UserFiles(context).dir).remoteButtons) return
        relayRetryAt = System.currentTimeMillis() + RELAY_RETRY_MS
        val created = RelayLink({}) { closed -> if (own === closed) own = null }
        val started = shell { sh ->
            RelayProtocol.STARTED in sh.runLasting(RelayProtocol.start(context.applicationInfo.nativeLibraryDir, created.port, created.token))
        }
        if (started == true && created.await(AWAIT_MS)) {
            created.send(RelayProtocol.keys(emptyList()))
            own = created
        } else {
            created.close()
        }
    }

    /** Runs [block] on the persistent connection, opening it if needed; null when ADB isn't usable. */
    private fun <T> shell(allowPrompt: Boolean = true, block: (AdbShell) -> T): T? {
        if (!PhoneAdb.enabled(context)) {
            PhoneAdb.failed(AdbShell.Failed.Off(null))
            return null
        }
        try {
            val sh = shell ?: PhoneAdb.create(context, allowPrompt && PhoneAdb.state != PhoneAdb.State.READY && PhoneAdb.claimPrompt()).also { shell = it }
            return block(sh).also { PhoneAdb.ready() }
        } catch (e: AdbShell.Failed) {
            shell?.close()
            shell = null
            PhoneAdb.failed(e)
        }
        return null
    }

    private companion object {
        const val QUEUE = 64
        const val AWAIT_MS = 8_000
        const val RELAY_RETRY_MS = 60_000L
    }
}
