package cloud.samlo.couch

import android.service.notification.NotificationListenerService

/**
 * Lets Couch read active media sessions (reading other apps' sessions requires notification
 * access, granted once in settings or with
 * `adb shell cmd notification allow_listener cloud.samlo.couch/cloud.samlo.couch.MediaListener`),
 * and, on Fire TV, the recommendations apps post as notifications (see `data.Recommendations`).
 */
class MediaListener : NotificationListenerService() {
    override fun onListenerConnected() {
        connected = this
    }

    override fun onListenerDisconnected() {
        if (connected === this) connected = null
    }

    companion object {
        /** The bound listener, while Android keeps it connected; null without notification access. */
        @Volatile
        var connected: MediaListener? = null
            private set
    }
}
