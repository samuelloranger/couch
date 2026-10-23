package cloud.samlo.couch.settings

import android.app.Activity
import android.view.KeyEvent
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.UserFiles

/** What every page shares: the activity, the config being edited, and the page's widgets. */
class SettingsContext(
    val activity: Activity,
    val files: UserFiles,
    val layout: SettingsLayout,
    /** Replaced when another screen (the icon picker) may have changed it on disk. */
    var config: Config,
) {
    val options = OptionList(activity, layout, { config }, files)

    fun str(id: Int): String = activity.getString(id)
}

/** One screen of settings, filled into the shared [SettingsLayout]. */
interface SettingsPage {
    fun build()

    /** Back from another screen. */
    fun onResume() = Unit

    /** The screen is going away: let go of anything held open. */
    fun onDestroy() = Unit

    /** True when the page handled the key itself. */
    fun consumesKey(event: KeyEvent): Boolean = false
}
