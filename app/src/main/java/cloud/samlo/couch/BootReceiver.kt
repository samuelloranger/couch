package cloud.samlo.couch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cloud.samlo.couch.godmode.Sleep

/** Restores HOME capture at boot, before anyone presses HOME, and drops a sleep timer the restart cancelled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        HomeAccessibilityService.restore(context)
        Sleep.forget(context)
    }
}
