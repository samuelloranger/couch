package cloud.samlo.couch

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Lets Couch read active media sessions (reading other apps' sessions requires notification
 * access, granted once in settings or with
 * `adb shell cmd notification allow_listener cloud.samlo.couch/cloud.samlo.couch.MediaListener`),
 * and, on Fire TV, the recommendations apps post as notifications (see `data.Recommendations`).
 */
class MediaListener : NotificationListenerService() {
    override fun onListenerConnected() {
        connected = this
        // The home screen may have loaded before Android bound us.
        onRecommendationsChanged?.invoke()
    }

    override fun onListenerDisconnected() {
        if (connected === this) connected = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = changed(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) = changed(sbn)

    private fun changed(sbn: StatusBarNotification) {
        if (sbn.notification?.category == Notification.CATEGORY_RECOMMENDATION) onRecommendationsChanged?.invoke()
    }

    companion object {
        /** The bound listener, while Android keeps it connected; null without notification access. */
        @Volatile
        var connected: MediaListener? = null
            private set

        /** Set by the home screen while it shows; called on the main thread. */
        var onRecommendationsChanged: (() -> Unit)? = null
    }
}
