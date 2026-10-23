package cloud.samlo.couch.godmode

import android.view.KeyEvent

/**
 * ↑ ↑ ↓ ↓ ← → ← → then OK on the home screen opens godmode, the second way in. The arrows stay
 * ordinary moves; only the final OK is taken, and only when the eight presses before it spell the
 * code with no more than [GAP_MS] between any two. Pure, so tests can play it at any speed.
 */
class Konami {
    private val recent = ArrayDeque<Int>()
    private var lastAt = 0L

    /** One key down; true when it completed the code (the caller swallows that OK). */
    fun press(keyCode: Int, atMs: Long): Boolean {
        if (atMs - lastAt > GAP_MS) recent.clear()
        lastAt = atMs
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            val hit = recent.toList() == CODE
            recent.clear()
            return hit
        }
        recent.addLast(keyCode)
        while (recent.size > CODE.size) recent.removeFirst()
        return false
    }

    private companion object {
        const val GAP_MS = 1500L
        val CODE = listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )
    }
}
