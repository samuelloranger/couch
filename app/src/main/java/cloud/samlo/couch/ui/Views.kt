package cloud.samlo.couch.ui

import android.content.Context
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextClock
import java.util.Locale

/**
 * A plain view for backgrounds, scrims and focus rings. Nothing inside it overlaps, so
 * skipping the offscreen layer keeps alpha fades cheap.
 */
class FlatView(context: Context) : View(context) {
    override fun hasOverlappingRendering() = false
}

/** Layout params filling the parent, for the stacked layers of a [FrameLayout]. */
fun fill() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

/** "Thursday, October 15" or "jeudi 15 octobre": weekday, day and month in the TV's language. */
fun TextClock.showLongDate() {
    val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM")
    format12Hour = pattern
    format24Hour = pattern
}

/**
 * Brings a hidden view in with a short fade and rise, over a hardware layer so its overlapping
 * children aren't redrawn offscreen each frame. A shown view stays as it is.
 */
fun View.fadeIn() {
    if (visibility == View.VISIBLE && alpha == 1f) return
    animate().cancel()
    if (visibility != View.VISIBLE) {
        alpha = 0f
        translationY = context.dpf(APPEAR_RISE_DP)
        visibility = View.VISIBLE
    }
    animate().alpha(1f).translationY(0f).setDuration(Tokens.APPEAR_MS).setInterpolator(Tokens.ease).withLayer().start()
}

/** Fades a shown view out, then removes it from the layout. */
fun View.fadeOut() {
    if (visibility != View.VISIBLE) return
    animate().cancel()
    animate().alpha(0f).setDuration(Tokens.FOCUS_OUT_MS).setInterpolator(Tokens.ease).withLayer().withEndAction {
        visibility = View.GONE
        alpha = 1f
    }.start()
}

/**
 * Fades in a view that was just shown: from transparent and a little low, by render properties
 * only (no layout, no layer). A running reveal restarts, so one view is never left half-faded.
 */
fun View.reveal() {
    animate().cancel()
    alpha = 0f
    translationY = context.dpf(APPEAR_RISE_DP)
    animate().alpha(1f).translationY(0f).setDuration(Tokens.APPEAR_MS).setInterpolator(Tokens.ease).start()
}

/** Shows or hides a view, fading it in when [animated]; either way settles a running reveal so no view keeps a partial alpha. */
fun View.appear(show: Boolean, animated: Boolean) {
    if (show == (visibility == View.VISIBLE)) return
    animate().cancel()
    alpha = 1f
    translationY = 0f
    visibility = if (show) View.VISIBLE else View.GONE
    if (show && animated) reveal()
}

private const val APPEAR_RISE_DP = 6f

