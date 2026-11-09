package cloud.samlo.couch.phone

import android.content.Context
import android.util.Log
import cloud.samlo.couch.adb.AdbShell
import cloud.samlo.couch.adb.RootShell
import cloud.samlo.couch.adb.Shell
import cloud.samlo.couch.capture.CaptureMath
import org.json.JSONObject

/**
 * The TV's picture for phones: one shared `screenrecord` per process, run through Couch's own ADB
 * on a connection of its own. It starts with the first phone that asks, is retuned when the
 * quality changes, and stops when no phone wants it. `screenrecord` ends after three minutes and
 * is started again at once; each start repeats its SPS/PPS. Everything that decides what is sent is
 * in [AnnexB], [AccessUnits] and [GopCache]; this class is the process around them.
 */
class Video(private val context: Context) {
    /** What a quality name means: the picture's size and the bit rate it is encoded at. */
    enum class Quality(val wire: String, val width: Int, val height: Int, val bitRate: Int) {
        HIGH("high", 1920, 1080, 8_000_000),
        MEDIUM("medium", 1280, 720, 4_000_000),
        LOW("low", 960, 540, 1_500_000),
        ;

        companion object {
            fun of(name: String?) = entries.firstOrNull { it.wire == name } ?: LOW
        }
    }

    private val lock = Any()
    private val wanting = LinkedHashSet<Session>()
    private val gop = GopCache()
    private var config: ByteArray? = null
    private var quality = Quality.LOW
    private var thread: Thread? = null
    private var shell: Shell? = null
    @Volatile private var generation = 0
    /** Set once the capture helper has failed here: the rest of the process uses `screenrecord`. */
    @Volatile private var helperFailed = false
    private var state = "ended"
    private var message: String? = null
    /** When the first byte of the access unit being built was read, for the timing log. */
    private var readAt = 0L
    private var logAt = 0L
    private var logUnits = 0
    private var logNanos = 0L
    private var logDepth = 0

    /** A phone asks for the picture, at [quality]; a different quality retunes it for everyone. */
    fun start(session: Session, requested: Quality) {
        synchronized(lock) {
            val retune = thread != null && requested != quality
            quality = requested
            session.resetVideo()
            wanting.add(session)
            if (retune) restart()
            if (thread == null) launch()
            session.send(status())
            if (!retune) replay(session)
        }
    }

    fun stop(session: Session) {
        synchronized(lock) {
            if (!wanting.remove(session)) return
            if (wanting.isEmpty()) halt("ended", null)
            session.send(status())
        }
    }

    /** What a phone that joins mid-stream needs first: the decoder's config, then the frames since the last keyframe. */
    private fun replay(session: Session) {
        config?.let { session.sendVideo(it, false, force = true) }
        gop.replay().forEach { session.sendVideo(it.frame(), it.key, force = true) }
    }

    private fun launch() {
        // Earlier sources of ours may have left a root-side screenrecord running.
        RootShell.killTracked()
        val mine = ++generation
        state = "starting"
        message = null
        config = null
        gop.clear()
        thread = Thread({ run(mine) }, "couch-phone-video").apply { isDaemon = true; start() }
        broadcast()
    }

    /** Ends the running source (a new one may be started after) without telling anyone it ended. */
    private fun restart() {
        generation++
        shell?.close()
        thread = null
        launch()
    }

    private fun halt(next: String, why: String?) {
        generation++
        shell?.close()
        RootShell.killTracked()
        shell = null
        thread = null
        config = null
        gop.clear()
        state = next
        message = why
        broadcast()
    }

    private fun current(mine: Int) = mine == generation

    private fun run(mine: Int) {
        var quick = 0
        while (current(mine)) {
            var began = System.currentTimeMillis()
            var frames = 0
            var gotConfig = false
            val q = synchronized(lock) { quality }
            val units = AccessUnits({ unit -> frames++; publish(mine, unit) }, { sps, pps -> gotConfig = true; newConfig(mine, sps, pps) })
            val splitter = AnnexB(units::add)
            val helper = !helperFailed
            val sh = PhoneAdb.create(context, true)
            if (sh == null) {
                PhoneAdb.failed(AdbShell.Failed.Off(null))
                synchronized(lock) { if (current(mine)) halt("needs-adb", PhoneAdb.message()) }
                return
            }
            synchronized(lock) { if (current(mine)) shell = sh else return sh.close() }
            var gaveUp = false
            try {
                val command = if (helper) {
                    CaptureMath.command(context.applicationInfo.sourceDir, q.width, q.height, q.bitRate, MAX_FPS)
                } else {
                    "screenrecord --output-format=h264 --size ${q.width}x${q.height} --bit-rate ${q.bitRate} -"
                }
                // The helper's clock starts now: finding a shell (the root probe, the debugging question) doesn't count.
                began = System.currentTimeMillis()
                sh.exec(
                    command,
                    IDLE_MS,
                    {
                        splitter.flush(); units.flush()
                        if (helper && !gaveUp && CaptureMath.fallBack(System.currentTimeMillis() - began, false, gotConfig)) {
                            gaveUp = true
                            sh.close()
                        }
                    },
                ) {
                    PhoneAdb.ready(sh)
                    if (readAt == 0L) readAt = System.nanoTime()
                    splitter.feed(it)
                }
            } catch (e: AdbShell.Failed) {
                if (!current(mine)) return
                if (gaveUp) {
                    helperFailed = true
                    continue
                }
                PhoneAdb.failed(e)
                synchronized(lock) { if (current(mine)) halt("needs-adb", PhoneAdb.message()) }
                return
            } finally {
                sh.close()
            }
            if (!current(mine)) return
            if (helper) {
                // The helper has no time limit: ending is a failure unless it ran long with a picture.
                if (gaveUp || CaptureMath.fallBack(System.currentTimeMillis() - began, true, gotConfig)) {
                    Log.i("couch-phone", "capture helper unavailable, using screenrecord")
                    helperFailed = true
                    RootShell.killTracked()
                }
                continue
            }
            // A run that gave nothing at once (a size the encoder refuses) is not worth repeating forever.
            quick = if (frames == 0 && System.currentTimeMillis() - began < QUICK_MS) quick + 1 else 0
            if (quick >= MAX_QUICK) {
                synchronized(lock) { if (current(mine)) halt("error", "Screen recorder failed.") }
                return
            }
        }
    }

    private fun newConfig(mine: Int, sps: ByteArray, pps: ByteArray) = synchronized(lock) {
        if (!current(mine)) return
        val frame = Wire.config(sps, pps)
        config = frame
        wanting.toList().forEach { it.sendVideo(frame, false, force = true) }
    }

    private fun publish(mine: Int, unit: AccessUnit) = synchronized(lock) {
        if (!current(mine)) return
        gop.add(unit)
        if (state != "live") {
            state = "live"
            broadcast()
        }
        val frame = unit.frame()
        val oversized = wanting.toList().filter { !it.sendVideo(frame, unit.key) }
        log(wanting.maxOfOrNull { it.depth } ?: 0)
        // A frame that can't fit a sealed frame can't be sent at any quality setting this phone is on.
        if (oversized.isNotEmpty() && unit.key) halt("error", "Picture too large. Lower the quality.")
    }

    /** One line a second while streaming: units, mean ms from first byte read to the writer's queue, deepest queue. */
    private fun log(depth: Int) {
        val now = System.nanoTime()
        if (readAt != 0L) logNanos += now - readAt
        readAt = 0L
        logUnits++
        logDepth = maxOf(logDepth, depth)
        if (logAt == 0L) logAt = now
        if (now - logAt < 1_000_000_000L) return
        val tenths = logNanos / 100_000 / logUnits
        Log.i("couch-phone", "au=$logUnits avgMs=${tenths / 10}.${tenths % 10} queue=$logDepth")
        logAt = now
        logUnits = 0
        logNanos = 0
        logDepth = 0
    }

    private fun status(): JSONObject {
        val q = quality
        return JSONObject().put("t", "stream.status").put("state", state).put("width", q.width).put("height", q.height)
            .apply { message?.let { put("message", it) } }
    }

    private fun broadcast() {
        val status = status()
        // A copy: sending can close a session, which stops it, which edits the set.
        wanting.toList().forEach { it.send(status) }
    }

    private companion object {
        /** The last frame before a pause is closed after this much quiet; otherwise the next frame's start ends it. */
        const val IDLE_MS = 50
        const val QUICK_MS = 3_000L
        const val MAX_QUICK = 3
        /** The most frames a second the helper's encoder is given; the TV's own refresh does the rest. */
        const val MAX_FPS = 60
    }
}
