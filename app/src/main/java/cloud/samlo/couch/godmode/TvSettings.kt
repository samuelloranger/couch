package cloud.samlo.couch.godmode

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import cloud.samlo.couch.data.Prefs

/**
 * The TV-wide settings earlier versions of Godmode changed (animation speed, private DNS), all in
 * `Settings.Global`. Their originals were kept in preferences before the first change, so [restore]
 * can still put the TV back as it was; a setting that was never set is restored by clearing it,
 * which means Android's default. Android's own settings change these now.
 */
internal object TvSettings {
    private val ANIMATIONS = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
    private const val DNS_MODE = "private_dns_mode"
    private const val DNS_HOST = "private_dns_specifier"

    private const val ORIGINAL = "godmode:original:"
    private const val ABSENT = "\u0000"

    fun canWrite(context: Context) =
        context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    private fun get(context: Context, key: String): String? = Settings.Global.getString(context.contentResolver, key)

    /** How many of godmode's settings differ from how the TV came: the animations count as one. */
    fun changed(context: Context): Int {
        val prefs = Prefs.of(context)
        val groups = listOf(ANIMATIONS, listOf(DNS_MODE, DNS_HOST))
        return groups.count { group -> group.any { prefs.contains(ORIGINAL + it) && original(context, it) != get(context, it) } }
    }

    /** Puts every setting godmode touched back as it was, and forgets the originals. */
    fun restore(context: Context) {
        val prefs = Prefs.of(context)
        val keys = prefs.all.keys.filter { it.startsWith(ORIGINAL) }
        keys.forEach { Settings.Global.putString(context.contentResolver, it.removePrefix(ORIGINAL), original(context, it.removePrefix(ORIGINAL))) }
        prefs.edit().apply { keys.forEach(::remove) }.apply()
    }

    private fun original(context: Context, key: String): String? =
        Prefs.of(context).getString(ORIGINAL + key, null)?.takeIf { it != ABSENT }
}
