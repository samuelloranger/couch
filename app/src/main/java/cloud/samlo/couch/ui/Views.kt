package cloud.samlo.couch.ui

import android.content.Context
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
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

private const val APPEAR_RISE_DP = 6f

/**
 * Call just before reordering [views] in their parent. Once the next layout has placed them,
 * each glides from where it was drawn to its new place: translation only, so the move costs one
 * layout pass, not one per frame. The first view must stay attached (the focused one).
 */
fun glideToNewPlaces(vararg views: View) {
    val anchor = views.first()
    val before = views.map { floatArrayOf(it.left + it.translationX, it.top + it.translationY) }
    anchor.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
        override fun onPreDraw(): Boolean {
            anchor.viewTreeObserver.removeOnPreDrawListener(this)
            views.forEachIndexed { i, view ->
                view.translationX = before[i][0] - view.left
                view.translationY = before[i][1] - view.top
                view.animate().translationX(0f).translationY(0f).setDuration(Tokens.GLIDE_MS).setInterpolator(Tokens.ease).start()
            }
            return true
        }
    })
}
