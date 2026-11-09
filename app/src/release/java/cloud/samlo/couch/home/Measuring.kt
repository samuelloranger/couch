package cloud.samlo.couch.home

import android.app.Activity
import android.view.KeyEvent
import android.view.View

/** Release builds carry no frame HUD or benchmark: the working twin lives in src/debug. */
@Suppress("UNUSED_PARAMETER")
class Measuring(activity: Activity) {
    val running = false

    fun enable(unit: String) = Unit

    fun onKey(event: KeyEvent) = Unit

    fun abortOn(event: KeyEvent) = false

    fun cancel() = Unit

    fun run(fps: Boolean, onCard: (View) -> Unit) = Unit

    companion object {
        const val BUILT = false
    }
}
