package cloud.samlo.couch.settings

import android.app.Activity
import android.app.AlertDialog
import android.widget.EditText
import android.widget.FrameLayout
import cloud.samlo.couch.R
import cloud.samlo.couch.ui.dp

/** Dialogs used from both the home screen and settings. */
object Dialogs {
    const val THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert

    /** One-line text entry; an empty answer means "reset". */
    fun text(activity: Activity, title: String, current: String, hint: String, onDone: (String) -> Unit) {
        val field = EditText(activity).apply {
            setText(current)
            setSelection(current.length)
            this.hint = hint
            isSingleLine = true
        }
        val frame = FrameLayout(activity).apply {
            val pad = activity.dp(20)
            setPadding(pad, pad / 2, pad, 0)
            addView(field)
        }
        AlertDialog.Builder(activity, THEME)
            .setTitle(title)
            .setView(frame)
            .setPositiveButton(R.string.ok) { _, _ -> onDone(field.text.toString().trim()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
        field.requestFocus()
    }
}
