package cloud.samlo.couch.home

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.tv.TvInputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import cloud.samlo.couch.data.Sections
import cloud.samlo.couch.ui.Rows

/**
 * Listens for installs, removals and TV input changes. Anything that changes what the rows
 * contain calls [onChanged]; an input merely (dis)connecting is a cheap in-place dim instead,
 * and one that turns on also calls [onInputWoke] with its port's input id.
 */
class SystemWatchers(
    private val activity: Activity,
    private val rows: Rows,
    private val onChanged: () -> Unit,
    private val onInputWoke: (String) -> Unit,
) {
    private val inputs = activity.getSystemService(TvInputManager::class.java)
    /** Each input's last known state; an input first heard of now has no "before" to wake from. */
    private val states = HashMap<String, Int>()

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onChanged()
    }

    private val inputCallback = object : TvInputManager.TvInputCallback() {
        override fun onInputStateChanged(inputId: String, state: Int) {
            // Cheap in-place update: no rebuild, no layout pass.
            rows.section(Sections.INPUTS)?.row?.cards()?.firstOrNull { it.tile.inputId == inputId }
                ?.setDimmed(state == TvInputManager.INPUT_STATE_DISCONNECTED)
            val before = states.put(inputId, state)
            if (state == TvInputManager.INPUT_STATE_CONNECTED && before != null && before != state) {
                // A CEC device reports as a child of its HDMI port; the Inputs row knows the port.
                onInputWoke(inputs?.getTvInputInfo(inputId)?.parentId ?: inputId)
            }
        }

        override fun onInputAdded(inputId: String) {
            inputs?.let { states[inputId] = it.getInputState(inputId) }
            onChanged()
        }

        override fun onInputRemoved(inputId: String) = onChanged()
        override fun onInputUpdated(inputId: String) = onChanged()
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            activity.registerReceiver(packageReceiver, filter)
        }
        inputs?.let { manager ->
            manager.tvInputList.forEach { states[it.id] = manager.getInputState(it.id) }
            manager.registerCallback(inputCallback, Handler(Looper.getMainLooper()))
        }
    }

    fun unregister() {
        activity.unregisterReceiver(packageReceiver)
        inputs?.unregisterCallback(inputCallback)
    }
}
