package cloud.samlo.couch.settings

import android.app.Activity
import android.view.View
import cloud.samlo.couch.ui.Popover

/** Popovers used from both the home screen and settings. */
object Dialogs {
    /** One-line text entry beside [anchor]; an empty answer means "reset". */
    fun text(activity: Activity, anchor: View, current: String, hint: String, onDone: (String) -> Unit) {
        Popover.text(activity, anchor, current, hint, onDone).show()
    }
}
