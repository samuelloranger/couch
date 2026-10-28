package cloud.samlo.couch.remote

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Couch's end of the line to the relay: a socket on 127.0.0.1 that only a client knowing [token]
 * may use, since any app on the TV can reach a local port. [await] blocks a worker until the
 * relay says hello, then a reader thread hands each line to [onLine]; [onClosed] gets the link
 * once when the line ends, which also ends the relay.
 */
class RelayLink(private val onLine: (String) -> Unit, private val onClosed: (RelayLink) -> Unit) {
    val token: String = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort
    @Volatile private var socket: Socket? = null
    @Volatile private var closed = false
    /** Writes leave the caller's thread: Android forbids socket I/O on the main one. */
    private val writer = Executors.newSingleThreadExecutor()

    /** Waits up to [timeoutMs] for the relay; false when it never came. */
    fun await(timeoutMs: Int): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        server.soTimeout = timeoutMs
        while (!closed && System.currentTimeMillis() < until) {
            val client = try {
                server.accept()
            } catch (e: SocketTimeoutException) {
                return false
            } catch (e: IOException) {
                return false
            }
            client.soTimeout = HELLO_MS
            val hello = runCatching { client.getInputStream().bufferedReader().readLine() }.getOrNull()
            if (hello != "hello $token") {
                runCatching { client.close() }
                continue
            }
            client.soTimeout = 0
            socket = client
            runCatching { server.close() }
            Thread({ read(client) }, "couch-relay-link").apply { isDaemon = true }.start()
            return true
        }
        return false
    }

    fun send(line: String) {
        val s = socket ?: return
        if (closed) return
        runCatching { writer.execute {
            try {
                s.getOutputStream().apply {
                    write("$line\n".toByteArray())
                    flush()
                }
            } catch (e: IOException) {
                close()
            }
        } }
    }

    fun close() {
        if (closed) return
        closed = true
        writer.shutdown()
        runCatching { server.close() }
        runCatching { socket?.close() }
        if (socket != null) onClosed(this)
    }

    private fun read(client: Socket) {
        try {
            client.getInputStream().bufferedReader().forEachLine(onLine)
        } catch (e: IOException) {
            // The relay went away or the link was closed: either way, it's over.
        }
        close()
    }

    private companion object {
        const val HELLO_MS = 3_000
    }
}
