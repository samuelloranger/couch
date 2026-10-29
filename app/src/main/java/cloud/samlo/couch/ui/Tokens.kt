package cloud.samlo.couch.ui

import android.graphics.Color
import android.view.animation.PathInterpolator
import cloud.samlo.couch.data.Accents

/**
 * Design tokens. The UI is laid out in dp on a 960x540dp canvas (1080p at 320dpi), and every
 * motion uses one curve so the whole screen shares a rhythm.
 */
object Tokens {
    val textPrimary = Color.parseColor("#EDEDEF")
    val textMuted = Color.parseColor("#8A9490")
    val surface = Color.parseColor("#1A201E")
    val surfaceFocused = Color.parseColor("#242B28")
    val ring = Color.parseColor("#FFFFFF")
    val background = Color.parseColor("#0A0D0C")

    /** Something the user should act on, such as a permission still off. */
    val warning = Color.parseColor("#FBBF24")

    /** Set from the user's choice before cards are built. */
    var accent = Color.parseColor(Accents.default)
    val glow: Int get() = (accent and 0x00FFFFFF) or 0x73000000

    /** Phosphor green for the godmode pages, whatever accent the user picked. */
    const val GODMODE_ACCENT = "#3DFF8A"
    val GODMODE_COLOR = Color.parseColor(GODMODE_ACCENT)

    fun applyAccent(hex: String) {
        accent = try {
            Color.parseColor(hex)
        } catch (e: IllegalArgumentException) {
            Color.parseColor(Accents.default)
        }
    }

    // TV title-safe area.
    const val OVERSCAN_H = 48
    const val OVERSCAN_V = 27

    const val CARD_GAP = 16
    const val CARD_RADIUS = 12f

    /** Space reserved around a card's content for the focus ring and glow. */
    const val RING_INSET = 6

    const val INPUT_W = 128
    const val INPUT_H = 72
    const val APP_W = 164
    const val APP_H = 92

    /** Row and column glides; long enough to read as motion, short enough to keep up with key repeat. */
    const val GLIDE_MS = 260L

    const val FOCUS_SCALE = 1.08f
    const val PRESS_SCALE = 1.02f
    const val FOCUS_IN_MS = 200L
    const val FOCUS_OUT_MS = 140L
    const val PRESS_MS = 80L

    /** Chips and pills that come and go, and card art arriving after its card. */
    const val APPEAR_MS = 220L

    /** Expo-out: fast start, long soft landing. */
    val ease = PathInterpolator(0.16f, 1f, 0.3f, 1f)
}
