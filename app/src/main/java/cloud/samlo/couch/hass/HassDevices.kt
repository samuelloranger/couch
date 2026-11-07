package cloud.samlo.couch.hass

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors

/**
 * The chosen lights and plugs as last known, and the commands sent to them, shared by the Index's
 * row and the remote's Input panel. Called and answering on the main thread; [onChange] runs after
 * every change, local or from Home Assistant.
 *
 * Commands are optimistic: the state changes at once and Home Assistant is told behind it; if it
 * refuses, the state goes back. Each device carries a version bumped by every command, so a read
 * that left before a press can't undo it.
 *
 * Home Assistant accepts a command before the device reports its new state (a cloud plug takes a
 * second or two), so a read right after still shows the old one. A command's state is kept and
 * checked again a few times; only the last check may overrule it.
 */
class HassDevices(private val hass: HomeAssistant, private val onChange: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "couch-hass").apply { isDaemon = true } }
    private val known = HashMap<String, HassDevice>()
    private val versions = HashMap<String, Int>()
    /** Devices whose last command Home Assistant hasn't confirmed yet: plain reads leave them alone. */
    private val unconfirmed = HashSet<String>()
    private var readAt = 0L
    private var dimmingId: String? = null
    private val sendDimming = Runnable { dimmingId?.let { send(it) } }

    var url = ""
        private set
    var ids: List<String> = emptyList()
        private set
    /** False once a read found Home Assistant down, until one reaches it again. */
    var reachable = true
        private set
    /** The device whose last command Home Assistant refused, until the next command or read. */
    var failed: String? = null
        private set

    operator fun get(id: String): HassDevice? = known[id]

    /** The devices to follow; true when they changed. */
    fun use(url: String, ids: List<String>): Boolean {
        val base = HassJson.baseUrl(url)
        val wanted = if (hass.connected(base)) ids.filter { HassDevice.supported(it) } else emptyList()
        if (base == this.url && wanted == this.ids) return false
        this.url = base
        this.ids = wanted
        readAt = 0L
        return true
    }

    /** States read elsewhere (a full list on a worker), for devices not known yet. */
    fun seed(states: Collection<HassDevice>) {
        states.forEach { if (it.id in ids && it.id !in known) known[it.id] = it }
        reachable = true
        onChange()
    }

    /** Reads every device again unless that happened a moment ago. */
    fun refresh() {
        if (ids.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (now - readAt < MIN_READ_MS) return
        readAt = now
        val asked = ids.associateWith { versions[it] ?: 0 }
        val base = url
        worker.execute {
            val read = asked.keys.associateWith { hass.state(base, it) }
            main.post {
                reachable = read.values.any { it != null }
                read.forEach { (id, d) -> if (d != null && (versions[id] ?: 0) == asked[id] && id !in unconfirmed) known[id] = d }
                failed = null
                onChange()
            }
        }
    }

    /** Switches [id] on or off; false when it can't be (not read yet, offline, Home Assistant down). */
    fun toggle(id: String): Boolean {
        val before = known[id] ?: return false.also { refresh() }
        if (!reachable || !before.available) return false
        known[id] = before.copy(on = !before.on)
        failed = null
        onChange()
        val version = bump(id)
        val base = url
        worker.execute {
            val ok = hass.toggle(base, id)
            main.post { settle(id, version, ok, before) }
        }
        return true
    }

    fun canDim(id: String): Boolean {
        val d = known[id] ?: return false
        return reachable && d.available && d.dimmable
    }

    /** Starts dimming [id]; a light that was off comes on at its last level. */
    fun startDimming(id: String) {
        val d = known[id] ?: return
        dimmingId = id
        if (!d.on) known[id] = d.copy(on = true, brightness = d.brightness.coerceAtLeast(STEP))
        onChange()
    }

    fun isDimming(id: String) = dimmingId == id

    /** One step brighter (1) or dimmer (-1); sent once the steps rest. */
    fun step(id: String, direction: Int) {
        val d = known[id] ?: return
        val level = ((d.brightness + direction * STEP) / STEP * STEP).coerceIn(STEP, 100)
        known[id] = d.copy(on = true, brightness = level)
        bump(id)
        onChange()
        main.removeCallbacks(sendDimming)
        main.postDelayed(sendDimming, BRIGHTNESS_SETTLE_MS)
    }

    /** Ends dimming, sending the last level at once. */
    fun stopDimming() {
        val id = dimmingId ?: return
        dimmingId = null
        main.removeCallbacks(sendDimming)
        send(id)
        onChange()
    }

    private fun send(id: String) {
        val wanted = known[id] ?: return
        val version = bump(id)
        val base = url
        worker.execute {
            val ok = hass.brightness(base, id, wanted.brightness)
            main.post { settle(id, version, ok, null) }
        }
    }

    /** A command's answer; ignored when a later command has already moved the device on. */
    private fun settle(id: String, version: Int, ok: Boolean, before: HassDevice?) {
        if (versions[id] != version) return
        if (!ok) {
            before?.let { known[id] = it }
            failed = id
            unconfirmed -= id
            onChange()
            return
        }
        unconfirmed += id
        confirm(id, version, 0)
    }

    /** Reads [id] again until Home Assistant shows what the command asked for, or the checks run out. */
    private fun confirm(id: String, version: Int, attempt: Int) {
        main.postDelayed({
            if (versions[id] != version) return@postDelayed
            val base = url
            worker.execute {
                val after = hass.state(base, id)
                main.post {
                    if (versions[id] != version) return@post
                    val wanted = known[id]
                    // Home Assistant keeps brightness out of 255: a percent may come back one off.
                    val agrees = after != null && wanted != null && after.on == wanted.on &&
                        (!after.on || !after.dimmable || Math.abs(after.brightness - wanted.brightness) <= 2)
                    val last = attempt == CONFIRM_MS.lastIndex
                    when {
                        after != null && (agrees || last) -> {
                            known[id] = after
                            unconfirmed -= id
                            onChange()
                        }
                        last -> unconfirmed -= id
                        else -> confirm(id, version, attempt + 1)
                    }
                }
            }
        }, CONFIRM_MS[attempt])
    }

    private fun bump(id: String): Int = ((versions[id] ?: 0) + 1).also { versions[id] = it }

    private companion object {
        const val STEP = 10
        /** Reads closer together than this are one read: focus moving pill to pill doesn't ask again. */
        const val MIN_READ_MS = 1500L
        /** Brightness goes out once the presses rest, not at every step. */
        const val BRIGHTNESS_SETTLE_MS = 350L
        /** When to check a command took: devices report back in well under a second to a few seconds. */
        val CONFIRM_MS = longArrayOf(1000L, 1500L, 3500L)
    }
}
