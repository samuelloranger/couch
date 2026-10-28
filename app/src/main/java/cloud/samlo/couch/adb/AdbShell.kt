package cloud.samlo.couch.adb

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One command over the TV's own adbd, at 127.0.0.1:5555 or the TV's LAN IPv4: connect, authorize,
 * open one shell stream, read its output, close.
 *
 * The ways a connection fails need different words in the UI, so they fail as distinct [Failed]
 * kinds: [Failed.Off] (nothing listens: debugging is off), [Failed.Blocked] (the TV drops the app
 * before adbd answers; Fire OS does this) and [Failed.NoOk] (Android's "Allow debugging?" question
 * got no Allow, or, with [prompt] off, the key isn't authorized any more).
 */
class AdbShell private constructor(
    private val key: AdbKey,
    private val prompt: Boolean,
    private val phase: (Phase) -> Unit,
) {
    private var live: Socket? = null
    private var stream = 0

    /** Steps worth showing to the user while a connection is negotiated. */
    enum class Phase { TRYING, DIALOG, READY }

    sealed class Failed(cause: Exception?) : Exception(cause) {
        class Off(cause: Exception?) : Failed(cause)
        class Blocked(cause: Exception?) : Failed(cause)
        class NoOk(cause: Exception?) : Failed(cause)
    }

    /**
     * Runs one shell command and returns its output. The first call connects, which is where
     * Android may ask to allow debugging; later calls reuse that connection as a new stream, so
     * one piece of work asks at most once however many commands it takes. [close] when done.
     */
    fun run(command: String): String = stream("shell:$command").toString(Charsets.UTF_8.name())

    /**
     * Like [run], over adbd's newer shell protocol. Anything the command leaves running in the
     * background then outlives the stream; the old protocol takes it down when the stream ends.
     */
    fun runLasting(command: String): String {
        val raw = stream("shell,v2,raw:$command").toByteArray()
        // Each v2 packet is an id byte, a little-endian length, and that many bytes; id 1 is stdout.
        val out = ByteArrayOutputStream()
        var at = 0
        while (at + 5 <= raw.size) {
            val length = ByteBuffer.wrap(raw, at + 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (length < 0 || at + 5 + length > raw.size) break
            if (raw[at].toInt() == STDOUT) out.write(raw, at + 5, length)
            at += 5 + length
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun stream(service: String): ByteArrayOutputStream {
        val socket = open()
        val local = ++stream
        try {
            write(socket, AdbProtocol.OPEN, local, 0, "$service\u0000".toByteArray())
            val out = ByteArrayOutputStream()
            while (true) {
                val m = read(socket)
                // Only this stream's packets; adbd answers an OPEN with arg1 set to our local id.
                if (m.arg1 != local) continue
                when (m.cmd) {
                    AdbProtocol.OKAY -> Unit
                    AdbProtocol.WRTE -> {
                        out.write(m.data)
                        write(socket, AdbProtocol.OKAY, local, m.arg0)
                    }
                    // Decoded once at the end, so a character split across two packets stays whole.
                    AdbProtocol.CLSE -> {
                        write(socket, AdbProtocol.CLSE, local, m.arg0)
                        return out
                    }
                    else -> {
                        // Out of step with adbd: this connection can't be trusted for the next command.
                        close()
                        throw Failed.Blocked(IllegalStateException("stream saw ${m.name()}"))
                    }
                }
            }
        } catch (e: IOException) {
            close()
            throw Failed.Blocked(e)
        }
    }

    /** The connection, opened and authorized on first use. */
    private fun open(): Socket = live ?: connectAny().also {
        live = it
        phase(Phase.READY)
    }

    /** Lets go of the connection; a later [run] opens a new one and may ask again. */
    fun close() {
        live?.let { runCatching { it.close() } }
        live = null
    }

    /**
     * Tries each address. A question on screen ends the search, so the TV never asks twice for one
     * attempt; otherwise a TV that drops the app outranks one where nothing listens.
     */
    private fun connectAny(): Socket {
        var blocked: Failed? = null
        var off: Failed? = null
        for (host in hosts()) {
            try {
                return connect(host)
            } catch (e: Failed.NoOk) {
                throw e
            } catch (e: Failed.Blocked) {
                blocked = blocked ?: e
            } catch (e: Failed.Off) {
                off = off ?: e
            }
        }
        throw blocked ?: off ?: Failed.Off(null)
    }

    private fun connect(host: String): Socket {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, PORT), CONNECT_MS)
        } catch (e: IOException) {
            socket.close()
            throw Failed.Off(e)
        }
        var asked = false
        try {
            socket.soTimeout = WAIT_MS
            socket.tcpNoDelay = true
            phase(Phase.TRYING)
            write(socket, AdbProtocol.CNXN, AdbProtocol.VERSION, AdbProtocol.MAX_PAYLOAD, HOST_BANNER)
            var signed = false
            while (true) {
                val m = read(socket)
                when {
                    m.cmd == AdbProtocol.CNXN -> return socket
                    m.cmd != AdbProtocol.AUTH || m.arg0 != TOKEN -> throw IOException("connect saw ${m.name()}")
                    !signed -> {
                        signed = true
                        write(socket, AdbProtocol.AUTH, SIGN, 0, key.sign(m.data))
                    }
                    // adbd doesn't know this key: sending it puts Android's question on screen.
                    !asked && prompt -> {
                        asked = true
                        phase(Phase.DIALOG)
                        write(socket, AdbProtocol.AUTH, PUBKEY, 0, key.adbdPublicKey())
                    }
                    else -> throw Failed.NoOk(null)
                }
            }
        } catch (e: Exception) {
            socket.close()
            throw when {
                e is Failed -> e
                // Once the question is up, a timeout or a hang-up means it wasn't allowed.
                asked || e is SocketTimeoutException -> Failed.NoOk(e)
                else -> Failed.Blocked(e)
            }
        }
    }

    private fun read(socket: Socket): AdbProtocol.Msg {
        val ins = DataInputStream(socket.getInputStream())
        val head = ByteArray(24)
        ins.readFully(head)
        val b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val data = ByteArray(b.getInt(12))
        if (data.isNotEmpty()) ins.readFully(data)
        return AdbProtocol.Msg(b.getInt(0), b.getInt(4), b.getInt(8), data)
    }

    private fun write(socket: Socket, cmd: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) =
        AdbProtocol.write(socket.getOutputStream(), cmd, arg0, arg1, data)

    private fun hosts() = listOf("127.0.0.1") + runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { !it.isLoopback && it.isUp }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress!! }
    }.getOrDefault(emptyList())

    companion object {
        private const val PORT = 5555
        private const val CONNECT_MS = 3000
        private const val WAIT_MS = 40_000
        private const val TOKEN = 1
        private const val SIGN = 2
        private const val PUBKEY = 3
        private const val STDOUT = 1
        private val HOST_BANNER = "host::\u0000".toByteArray()

        /**
         * One long-lived key per install; `couch@tv` is the name it carries in adbd's list. With
         * [prompt] off an unknown key fails instead of putting the question on screen.
         */
        fun create(context: Context, prompt: Boolean = true, phase: (Phase) -> Unit = {}) =
            AdbShell(AdbKey.load(context.filesDir, "couch@tv"), prompt, phase)
    }
}
