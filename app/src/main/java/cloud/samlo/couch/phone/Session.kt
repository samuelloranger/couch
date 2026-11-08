package cloud.samlo.couch.phone

import cloud.samlo.couch.sync.Peer
import cloud.samlo.couch.data.WallpaperUpload
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * One authenticated phone connection. A reader thread turns frames into messages for the
 * [listener]; a writer thread, fed by a queue, is the only thing that writes, so a slow phone
 * stalls itself and nothing else. JSON is never dropped (the session closes if a phone lets
 * [MAX_JSON_BYTES] pile up unread); video is dropped from the queue's end, up to the next
 * keyframe, once more than [MAX_VIDEO_BYTES] or [MAX_VIDEO_MS] of it waits.
 */
class Session(
    val peer: Peer,
    private val socket: Socket,
    private val input: DataInputStream,
    private val opener: FrameCipher,
    private val sealer: FrameCipher,
    private val first: ByteArray,
    private val listener: Listener,
) {
    interface Listener {
        fun onMessage(session: Session, message: JSONObject)
        fun onClosed(session: Session)
    }

    private class Item(val bytes: ByteArray, val video: Boolean, val at: Long = System.nanoTime())

    private val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
    private val queue = LinkedBlockingQueue<Item>()
    private val queuedVideo = AtomicInteger()
    private val queuedJson = AtomicInteger()
    @Volatile private var dropping = false
    @Volatile private var closed = false
    private var writer: Thread? = null

    /** Keys this phone holds down; let go of when the session ends. */
    val held = HeldKeys()
    val wallpaperUpload = WallpaperUpload()

    /** Whether this phone asked for the picture; set by the stream messages. */
    @Volatile var wantsVideo = false

    val isOpen get() = !closed

    /** Frames waiting for the writer. */
    val depth get() = queue.size

    fun start() {
        socket.soTimeout = IDLE_MS
        // Frames are sent whole; Nagle would hold a frame's tail back waiting for an ack.
        runCatching { socket.tcpNoDelay = true }
        writer = Thread(::write, "couch-phone-write").apply { isDaemon = true; start() }
        Thread(::read, "couch-phone-read").apply { isDaemon = true; start() }
    }

    /** A message too large for one frame is replaced by an `error` naming what it answered. */
    fun send(message: JSONObject) {
        val body = message.toString().toByteArray()
        if (body.size + 17 > Frames.MAX) {
            val about = message.optString("t")
            return send(JSONObject().put("t", "error").put("about", about).put("message", "Too large"))
        }
        sendBytes(byteArrayOf(Wire.JSON.toByte()) + body)
    }

    private fun sendBytes(bytes: ByteArray) {
        if (closed) return
        if (queuedJson.addAndGet(bytes.size) > MAX_JSON_BYTES) return close()
        queue.add(Item(bytes, false))
    }

    /**
     * Queues a video frame ([frame] is a `0x02` or `0x03` body). A frame that would make the queue
     * too long or old is dropped and so is everything after it until a keyframe finds the queue
     * drained. [force] skips that, for the catch-up a joining phone gets.
     */
    fun sendVideo(frame: ByteArray, key: Boolean, force: Boolean = false): Boolean {
        if (closed) return true
        // A frame that can never fit one sealed frame: the caller has to do something about it.
        if (frame.size + 16 > Frames.MAX) return false
        if (!force) {
            val head = queue.peek()
            val late = head != null && (System.nanoTime() - head.at) / 1_000_000 > MAX_VIDEO_MS && queuedVideo.get() > 0
            // An empty queue takes any frame that fits, so one big keyframe is never lost to the cap.
            if (late || (queuedVideo.get() > 0 && queuedVideo.get() + frame.size > MAX_VIDEO_BYTES)) {
                dropping = true
                return true
            }
            if (dropping) {
                if (!key) return true
                dropping = false
            }
        }
        queuedVideo.addAndGet(frame.size)
        queue.add(Item(frame, true))
        return true
    }

    /** A phone that joins mid-stream starts clean, from the config and keyframe it is about to be sent. */
    fun resetVideo() {
        dropping = false
    }

    private fun write() {
        try {
            while (!closed) {
                val item = queue.take()
                if (item.bytes.isEmpty()) break
                if (item.video) queuedVideo.addAndGet(-item.bytes.size) else queuedJson.addAndGet(-item.bytes.size)
                Frames.write(out, sealer, item.bytes)
            }
        } catch (e: IOException) {
            // The phone went away or stopped reading: the session is over.
        } catch (e: InterruptedException) {
            // Closed while waiting.
        }
        close()
    }

    private fun read() {
        try {
            var plain = first
            while (!closed) {
                deliver(plain)
                plain = Frames.read(input, opener)
            }
        } catch (e: IOException) {
            // A closed socket, a quiet phone (the read times out) or a frame that doesn't open.
        } catch (e: RuntimeException) {
            // A handler that broke on one message must not leave the session half alive.
        }
        close()
    }

    private fun deliver(plain: ByteArray) {
        if (plain[0].toInt() != Wire.JSON) return
        val message = try {
            JSONObject(String(plain, 1, plain.size - 1, Charsets.UTF_8))
        } catch (e: JSONException) {
            return
        }
        if (message.optString("t").isNotEmpty()) listener.onMessage(this, message)
    }

    fun close() {
        if (closed) return
        closed = true
        wallpaperUpload.close()
        queue.clear()
        queue.add(Item(ByteArray(0), false))
        writer?.interrupt()
        runCatching { socket.close() }
        listener.onClosed(this)
    }

    private companion object {
        /** The phone pings every 15 s; three missed pings and the line is dead. */
        const val IDLE_MS = 45_000
        const val MAX_VIDEO_BYTES = 2 * 1024 * 1024
        const val MAX_VIDEO_MS = 1_500L
        const val MAX_JSON_BYTES = 8 * 1024 * 1024
    }
}
