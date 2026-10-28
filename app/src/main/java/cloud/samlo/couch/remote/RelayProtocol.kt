package cloud.samlo.couch.remote

/** The lines Couch and the relay exchange (see `src/main/native/relay.c`). */
object RelayProtocol {
    sealed class Event {
        /** A key Couch asked for went down or up. */
        data class Key(val code: Int, val down: Boolean) : Event()

        /** While learning: a key went down, whichever it was. */
        data class Seen(val code: Int) : Event()
    }

    /** Linux key codes a remote needs for itself, never offered as a button to take over. */
    val RESERVED = setOf(
        28, 96, 352, 353, // Enter, keypad Enter, OK, Select
        103, 105, 106, 108, // D-pad
        102, 172, 158, // Home, Home page, Back
        113, 114, 115, // Mute, Volume down, Volume up
        116, 142, // Power, Sleep
    )

    const val BACK = 158

    fun parse(line: String): Event? {
        val parts = line.trim().split(' ')
        return when (parts.firstOrNull()) {
            "key" -> {
                val code = parts.getOrNull(1)?.toIntOrNull() ?: return null
                val value = parts.getOrNull(2)?.toIntOrNull() ?: return null
                Event.Key(code, value == 1)
            }
            "seen" -> parts.getOrNull(1)?.toIntOrNull()?.let { Event.Seen(it) }
            else -> null
        }
    }

    fun keys(codes: Collection<Int>): String = "keys " + codes.filter { it > 0 }.distinct().joinToString(",").ifEmpty { "-" }

    fun learn(on: Boolean) = "learn ${if (on) 1 else 0}"

    fun emit(code: Int) = "emit $code"

    /**
     * The shell command that installs the relay from the app's unpacked libraries and starts it.
     * The relay detaches itself, so it outlives the ADB stream. An older relay is stopped first; by its process
     * name, which this command's own shell doesn't carry. The copy is renamed into place, since a
     * relay still on its way out keeps its file busy for writing but not for replacing.
     */
    fun start(libDir: String, port: Int, token: String): String {
        val bin = "/data/local/tmp/couch-relay"
        return "pkill -x couch-relay; cp '$libDir/librelay.so' $bin.new && chmod 755 $bin.new && mv -f $bin.new $bin && " +
            "$bin $port $token && echo $STARTED"
    }

    /** What [start] prints when the relay was launched. */
    const val STARTED = "started"
}
