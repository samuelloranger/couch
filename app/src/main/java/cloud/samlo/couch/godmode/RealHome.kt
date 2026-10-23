package cloud.samlo.couch.godmode

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import cloud.samlo.couch.MainActivity
import cloud.samlo.couch.data.Prefs
import cloud.samlo.couch.debloat.Engine

/**
 * Makes Couch the TV's real home, so HOME opens it without the accessibility workaround, by
 * setting Android's own preferred home. Nothing is frozen or disabled: Android drops a preference
 * by itself when its app is uninstalled, so no state Couch leaves behind can strand the TV without
 * a home. A TV that ignores the preference keeps its stock home, and the accessibility workaround
 * goes on doing the job there.
 */
internal object RealHome {
    private const val PREVIOUS = "godmode:previousHome"

    fun active(context: Context) = Prefs.of(context).contains(PREVIOUS)

    fun isCouch(context: Context) = home(context)?.packageName == context.packageName

    /** True once HOME really resolves to Couch. Blocking, after [Engine.connect] granted the shell. */
    fun take(context: Context, engine: Engine): Boolean {
        // Where HOME goes back to: the home that was preferred, or else the stock launcher. Never
        // Android's chooser, which isn't a home and can't be preferred again.
        val back = home(context)?.takeIf { it.packageName != "android" && it.packageName != context.packageName } ?: stockHome(context)
        val me = ComponentName(context, MainActivity::class.java).flattenToShortString()
        engine.run("cmd package set-home-activity $me")
        if (!isCouch(context)) return false
        Prefs.of(context).edit().putString(PREVIOUS, back?.flattenToShortString().orEmpty()).apply()
        return true
    }

    /** Hands HOME back; true once verified. Blocking, after [Engine.connect]. */
    fun give(context: Context, engine: Engine): Boolean {
        val prefs = Prefs.of(context)
        prefs.getString(PREVIOUS, null)?.takeIf { it.isNotEmpty() }?.let { engine.run("cmd package set-home-activity $it") }
        // With no other home on the TV at all, Couch staying the home is the only possible state.
        if (isCouch(context) && stockHome(context) != null) return false
        prefs.edit().remove(PREVIOUS).apply()
        return true
    }

    private val homeIntent get() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    private fun home(context: Context): ComponentName? =
        context.packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            ?.let { ComponentName(it.packageName, it.name) }

    /** The other home with the highest priority, never a setup or settings app. */
    private fun stockHome(context: Context): ComponentName? =
        context.packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .sortedByDescending { it.priority }
            .map { it.activityInfo }
            .firstOrNull { it.packageName != context.packageName && it.packageName !in NOT_A_HOME }
            ?.let { ComponentName(it.packageName, it.name) }

    /** Apps that answer HOME only to finish setup or recover; never what HOME should go back to. */
    private val NOT_A_HOME = setOf("com.google.android.tungsten.setupwraith", "com.android.tv.settings", "com.android.settings", "com.android.provision", "android")
}
