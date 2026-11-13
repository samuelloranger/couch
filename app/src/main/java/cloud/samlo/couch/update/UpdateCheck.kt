package cloud.samlo.couch.update

import android.content.Context
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.Fetcher
import cloud.samlo.couch.data.Prefs

/**
 * Asks GitHub for the latest release at most once a day, like the weather: the answer is kept in
 * preferences, so a failed request changes nothing and the home screen and settings read it offline.
 */
class UpdateCheck(context: Context, private val fetcher: Fetcher) {
    private val prefs = Prefs.of(context)
    private val installed = Versions.installed(context)

    /** When the last check ran, in epoch millis; 0 before the first. */
    val checkedAt: Long get() = prefs.getLong(CHECKED_AT, 0L)

    /** True when the last check got no answer from GitHub. */
    val failed: Boolean get() = prefs.getBoolean(FAILED, false)

    /**
     * Asks GitHub when the last check is a day old, or at once when [force] (the settings' manual
     * check, which works with the daily check off). Blocking: run it off the main and loader threads
     * so a slow network never holds up a screen. True when it found a release newer than this build.
     */
    fun refresh(config: Config, force: Boolean = false): Boolean {
        if (!force && !config.checkUpdates) return false
        val now = System.currentTimeMillis()
        // A clock that jumped backwards (no real-time clock until the network time arrives) counts as due.
        if (!force && now - checkedAt in 0 until DAY_MS) return false
        // Recorded before the request, so an unreachable GitHub is asked once a day, not on every resume.
        prefs.edit().putLong(CHECKED_AT, now).apply()
        val text = fetcher.text(if (config.preReleases) LIST else LATEST)
        val release = text?.let { if (config.preReleases) Release.newestOf(it) else Release.fromGitHub(it) }
        prefs.edit().apply {
            putBoolean(FAILED, text == null)
            if (release != null) putString(LATEST_RELEASE, release.serialize())
        }.apply()
        return pending(config) != null
    }

    /** What the last check found, when it is newer than this build and the daily check is on. No network. */
    fun pending(config: Config): Release? = if (config.checkUpdates) found() else null

    /** What the last check found, when it is newer than this build, whether or not the daily check is on. */
    fun found(): Release? {
        val release = prefs.getString(LATEST_RELEASE, null)?.let(Release::parse) ?: return null
        return release.takeIf { Versions.isNewer(it.version, installed) }
    }

    companion object {
        private const val LATEST = "https://api.github.com/repos/samuelloranger/couch/releases/latest"
        /** The pre-release channel: the newest few releases, drafts and pre-releases included. */
        private const val LIST = "https://api.github.com/repos/samuelloranger/couch/releases?per_page=10"
        private const val CHECKED_AT = "update:checkedAt"
        private const val LATEST_RELEASE = "update:latest"
        private const val FAILED = "update:failed"
        private const val DAY_MS = 24 * 60 * 60_000L

        /** After a channel change: the next load asks GitHub again, and the old answer is dropped. */
        fun forget(context: Context) {
            Prefs.of(context).edit().remove(CHECKED_AT).remove(LATEST_RELEASE).remove(FAILED).apply()
        }
    }
}
