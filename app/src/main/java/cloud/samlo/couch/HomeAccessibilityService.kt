package cloud.samlo.couch

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import cloud.samlo.couch.data.Prefs
import cloud.samlo.couch.remote.RemoteButtons

/**
 * Some TVs hardwire HOME to the stock launcher. This service only receives window changes from
 * that launcher (see `res/xml/home_accessibility.xml`) and replaces it with Couch. It never
 * filters key events, so holding volume keeps ramping. It also draws the panels the remote's
 * buttons open when [RemoteButtons] is turned on.
 */
class HomeAccessibilityService : AccessibilityService() {
    private var remote: RemoteButtons? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!isStockHome(event.packageName?.toString(), event.className?.toString())) return
        openCouch()
    }

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = this
        remote = RemoteButtons(this).also { it.reload() }
        // Remember that HOME capture is wanted, so it can be restored after Android drops it.
        wanted(this, true)
        // A self-update restarts the app and leaves the stock launcher showing; come back to Couch.
        val prefs = Prefs.of(this)
        val since = System.currentTimeMillis() - prefs.getLong(REOPEN, 0L)
        prefs.edit().remove(REOPEN).apply()
        if (since in 0..REOPEN_WINDOW_MS) openCouch()
    }

    override fun onDestroy() {
        remote?.stop()
        remote = null
        if (running === this) running = null
        super.onDestroy()
    }

    private fun openCouch() = startActivity(
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
    )

    companion object {
        private const val WANTED = "homeCaptureWanted"
        private const val REOPEN = "update:reopenAt"
        /**
         * Launchers whose package also hosts other screens, mapped to their home screen: Fire TV
         * Settings lives in Amazon's, the profile chooser in Google TV's. Only the home screen is
         * replaced. Every other launcher in `res/xml/home_accessibility.xml` is replaced whole.
         */
        private val SHARED_LAUNCHERS = mapOf(
            "com.amazon.tv.launcher" to "com.amazon.tv.launcher.ui.HomeActivity_vNext",
            "com.google.android.apps.tv.launcherx" to "com.google.android.apps.tv.launcherx.home.HomeActivity",
        )

        /** True when a window from [pkg] with [cls] is the stock home screen Couch should replace. */
        fun isStockHome(pkg: String?, cls: String?): Boolean {
            val home = SHARED_LAUNCHERS[pkg] ?: return true
            return cls == home
        }
        /** An update that hasn't landed by then was cancelled or failed: never reopen on a later connect. */
        private const val REOPEN_WINDOW_MS = 10 * 60_000L
        private const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"

        /** The connected service, for godmode's global actions (sleep, screenshot); null when off. */
        @Volatile private var running: HomeAccessibilityService? = null

        /** The remote buttons of the connected service; null while the service is off. */
        fun remote(): RemoteButtons? = running?.remote

        /** Android's own global action (screen off, screenshot), through the service; false without it. */
        fun globalAction(action: Int): Boolean = running?.performGlobalAction(action) == true

        fun wanted(context: Context, value: Boolean) =
            Prefs.of(context).edit().putBoolean(WANTED, value).apply()

        /** Set just before an update is committed and cleared if it fails; the reconnecting service then reopens Couch. */
        fun reopenAfterUpdate(context: Context, pending: Boolean) {
            val edit = Prefs.of(context).edit()
            if (pending) edit.putLong(REOPEN, System.currentTimeMillis()) else edit.remove(REOPEN)
            edit.commit()
        }

        /**
         * Android removes an app's accessibility service from the enabled list whenever the app is
         * force-stopped, which silently hands HOME back to the stock launcher. With
         * WRITE_SECURE_SETTINGS (granted over ADB), put it back. Never enables it on its own:
         * only after the service has run once.
         */
        fun restore(context: Context) {
            if (!Prefs.of(context).getBoolean(WANTED, false)) return
            if (context.checkSelfPermission(WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
            if (isEnabled(context)) return
            val me = ComponentName(context, HomeAccessibilityService::class.java).flattenToString()
            val resolver = context.contentResolver
            val services = enabledServices(context)
            try {
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (services + me).distinct().joinToString(":"))
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            } catch (e: SecurityException) {
                // Permission revoked since the check: nothing to do.
            }
        }

        /** True while Android has the service turned on. */
        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, HomeAccessibilityService::class.java).flattenToString()
            return me in enabledServices(context) &&
                Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        }

        private fun enabledServices(context: Context) =
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                .split(':').filter { it.isNotBlank() }
    }
}
