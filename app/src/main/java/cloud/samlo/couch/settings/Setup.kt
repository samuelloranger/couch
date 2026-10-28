package cloud.samlo.couch.settings

import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import cloud.samlo.couch.HomeAccessibilityService
import cloud.samlo.couch.MediaListener
import cloud.samlo.couch.R
import cloud.samlo.couch.data.Programs
import cloud.samlo.couch.data.Usage

/**
 * One access Couch needs for a feature: whether Android has granted it, and where it is granted.
 * [inPlace] when Couch asks for it itself rather than opening one of Android's screens.
 */
class SetupItem(val title: Int, val help: Int, val isOn: (Context) -> Boolean, val inPlace: Boolean = false, val open: (Activity) -> Unit)

/**
 * The accesses that turn features on. Without them those features stay hidden rather than
 * failing, so this list is the one place that says what is missing.
 */
object Setup {
    val items = listOf(
        SetupItem(R.string.setup_home, R.string.setup_home_help, { isDefaultHome(it) || HomeAccessibilityService.isEnabled(it) }) {
            openSettings(it, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
        SetupItem(R.string.setup_notifications, R.string.setup_notifications_help, { notificationAccess(it) }) {
            openSettings(it, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        },
        SetupItem(R.string.setup_usage, R.string.setup_usage_help, { Usage.canRead(it) }) {
            openSettings(it, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        },
        SetupItem(R.string.setup_channels, R.string.setup_channels_help, { Programs.canRead(it) }, inPlace = true) { channels(it) },
    )

    fun allOn(context: Context) = items.all { it.isOn(context) }

    /** Couch declared as the home app (TVs that let you turn the stock home off) needs no HOME service. */
    private fun isDefaultHome(context: Context): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == context.packageName
    }

    private fun notificationAccess(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.isNotificationListenerAccessGranted(ComponentName(context, MediaListener::class.java))
    }

    /** Asked in place; see [onPermissionResult] for a refusal Android won't ask about again. */
    private fun channels(activity: Activity) =
        activity.requestPermissions(arrayOf(Programs.READ_TV_LISTINGS), REQUEST_CHANNELS)

    /**
     * After a refusal "for good", Android answers [channels] at once without asking: only the
     * app's own page can grant it then.
     */
    fun onPermissionResult(activity: Activity, requestCode: Int, results: IntArray) {
        if (requestCode != REQUEST_CHANNELS) return
        // An empty result is a cancelled request, not a refusal.
        if ((results.firstOrNull() ?: return) == PackageManager.PERMISSION_GRANTED) return
        if (activity.shouldShowRequestPermissionRationale(Programs.READ_TV_LISTINGS)) return
        openSettings(activity, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)))
    }

    /** Many TVs (Fire TV among them) leave these screens out; the help text then gives the ADB command. */
    private fun openSettings(activity: Activity, intent: Intent) {
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.setup_no_screen, Toast.LENGTH_LONG).show()
        }
    }

    private const val REQUEST_CHANNELS = 2
}
