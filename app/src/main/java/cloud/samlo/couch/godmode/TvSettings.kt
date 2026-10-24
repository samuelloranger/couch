package cloud.samlo.couch.godmode

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import cloud.samlo.couch.data.Prefs

/**
 * The TV-wide settings godmode changes, all in `Settings.Global`, which Couch may write once it
 * holds WRITE_SECURE_SETTINGS (the shell grants it). Before a setting is first changed, its
 * original value is kept in preferences, so [restore] can put the whole TV back as it was; a
 * setting that was never set is restored by clearing it, which means Android's default.
 */
internal object TvSettings {
    /** Android's three animation speeds, changed together. */
    val ANIMATIONS = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
    const val DNS_MODE = "private_dns_mode"
    const val DNS_HOST = "private_dns_specifier"

    /** A free, well-known ad-blocking resolver; any other is one custom hostname away. */
    const val ADBLOCK_HOST = "dns.adguard-dns.com"

    private const val ORIGINAL = "godmode:original:"
    private const val ABSENT = "\u0000"

    fun canWrite(context: Context) =
        context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    fun get(context: Context, key: String): String? = Settings.Global.getString(context.contentResolver, key)

    /** Writes [values] (null clears a key), keeping each one's original the first time. */
    fun set(context: Context, values: Map<String, String?>) {
        val prefs = Prefs.of(context)
        val edit = prefs.edit()
        values.keys.filterNot { prefs.contains(ORIGINAL + it) }.forEach { edit.putString(ORIGINAL + it, get(context, it) ?: ABSENT) }
        edit.apply()
        values.forEach { (key, value) -> Settings.Global.putString(context.contentResolver, key, value) }
    }

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

    /** The animation speed as one of "0", "0.5" or "1"; anything unset reads as Android's 1×. */
    fun animations(context: Context): String = when (get(context, ANIMATIONS[0])?.toFloatOrNull() ?: 1f) {
        0f -> "0"
        0.5f -> "0.5"
        else -> "1"
    }

    fun setAnimations(context: Context, scale: String) = set(context, ANIMATIONS.associateWith { scale })

    /** "off", "auto" (Android's opportunistic default), "adblock" or "custom". */
    fun dns(context: Context): String = when (get(context, DNS_MODE)) {
        "off" -> "off"
        "hostname" -> if (get(context, DNS_HOST) == ADBLOCK_HOST) "adblock" else "custom"
        else -> "auto"
    }

    fun setDns(context: Context, choice: String, customHost: String? = null) = set(
        context,
        when (choice) {
            "off" -> mapOf(DNS_MODE to "off")
            "adblock" -> mapOf(DNS_MODE to "hostname", DNS_HOST to ADBLOCK_HOST)
            "custom" -> mapOf(DNS_MODE to "hostname", DNS_HOST to customHost)
            else -> mapOf(DNS_MODE to "opportunistic")
        },
    )
}
