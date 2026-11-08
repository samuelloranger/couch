package cloud.samlo.couch.phone

import java.util.concurrent.ConcurrentHashMap

/** The key codes a phone pressed down and has not let go of, so a session that ends can let go for it. */
class HeldKeys {
    private val codes = ConcurrentHashMap.newKeySet<Int>()

    fun down(code: Int) {
        codes.add(code)
    }

    fun up(code: Int) {
        codes.remove(code)
    }

    /** Everything still held, forgotten as it is returned. */
    fun drain(): List<Int> = codes.toList().also { codes.clear() }
}
