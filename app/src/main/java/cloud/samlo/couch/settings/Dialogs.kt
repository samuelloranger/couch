package cloud.samlo.couch.settings

import android.app.Activity
import android.app.AlertDialog
import android.widget.EditText
import android.widget.FrameLayout
import cloud.samlo.couch.R
import cloud.samlo.couch.data.AppSource
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.InputSource
import cloud.samlo.couch.ui.dp
import java.io.File

/** Dialogs used from both the home screen and settings. */
object Dialogs {
    const val THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert

    /** Every app and input in one list; unchecking hides it. */
    fun visibility(activity: Activity, config: Config, userDir: File?, onDone: () -> Unit) {
        val pm = activity.packageManager
        val apps = AppSource.launchable(pm)
            .map { it.activityInfo.packageName to (config.names[it.activityInfo.packageName] ?: it.loadLabel(pm).toString()) }
            .filter { it.first != activity.packageName }
            .sortedBy { it.second.lowercase() }
        val inputs = InputSource.all(activity, config).map { it.key to it.label }
        val labels = apps.map { it.second } + inputs.map { activity.getString(R.string.input_prefix, it.second) }
        val checked = BooleanArray(labels.size) { i ->
            if (i < apps.size) apps[i].first !in config.hiddenApps else inputs[i - apps.size].first !in config.hiddenInputs
        }
        AlertDialog.Builder(activity, THEME)
            .setTitle(R.string.visibility_title)
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.ok) { _, _ ->
                config.hiddenApps = apps.filterIndexed { i, _ -> !checked[i] }.map { it.first }.toSet() +
                    // Apps that are hidden but no longer installed stay hidden if they come back.
                    config.hiddenApps.filter { pkg -> apps.none { it.first == pkg } }
                config.hiddenInputs = inputs.filterIndexed { i, _ -> !checked[apps.size + i] }.map { it.first }.toSet()
                config.save(userDir)
                onDone()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

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
