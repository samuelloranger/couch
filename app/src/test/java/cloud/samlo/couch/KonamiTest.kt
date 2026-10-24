package cloud.samlo.couch

import android.view.KeyEvent
import cloud.samlo.couch.godmode.Konami
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The code opens godmode on its OK only; anything else is an ordinary press. */
class KonamiTest {
    private val code = intArrayOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    )

    private fun play(k: Konami, keys: IntArray, gapMs: Long = 200, start: Long = 1000): Long {
        var t = start
        keys.forEach { assertFalse(k.press(it, t)); t += gapMs }
        return t
    }

    @Test fun theCodeThenOkOpens() {
        val k = Konami()
        val t = play(k, code)
        assertTrue(k.press(KeyEvent.KEYCODE_DPAD_CENTER, t))
    }

    @Test fun extraUpsBeforeStillCount() {
        val k = Konami()
        val t = play(k, intArrayOf(KeyEvent.KEYCODE_DPAD_UP) + code)
        assertTrue(k.press(KeyEvent.KEYCODE_ENTER, t))
    }

    @Test fun aSlowPressBreaksIt() {
        val k = Konami()
        val t = play(k, code, gapMs = 1600)
        assertFalse(k.press(KeyEvent.KEYCODE_DPAD_CENTER, t))
    }

    @Test fun okAloneOrAWrongArrowDoesNothing() {
        val k = Konami()
        assertFalse(k.press(KeyEvent.KEYCODE_DPAD_CENTER, 0))
        val wrong = code.copyOf().also { it[5] = KeyEvent.KEYCODE_DPAD_LEFT }
        val t = play(k, wrong)
        assertFalse(k.press(KeyEvent.KEYCODE_DPAD_CENTER, t))
    }
}
