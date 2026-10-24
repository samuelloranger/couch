package cloud.samlo.couch.debloat

import cloud.samlo.couch.R

/**
 * What Couch knows about the TV's own apps, shipped as small per-brand lists ([Catalog]): the
 * big community list is written for phones, needs 1.6 MB, and carries none of the `com.sony.dtv.*`
 * packages. Each entry's description is a shared [Why], so a new brand costs no new strings.
 *
 * Nothing outside these lists is ever offered or removed, and [isProtected] packages never are,
 * whatever a list says: removing those (home, WebView, Play services, input method…) can leave a
 * TV that won't start until a factory reset. Apps people regret removing are [Keep]: shown, never
 * offered.
 */
class DebloatList private constructor(private val catalog: Catalog, private val entries: List<Entry>) {
    enum class Level { SAFE, ASK, EXPERT }

    /** What a kind of app does, and what removing it costs: the row's help text. */
    enum class Why(val text: Int) {
        ADS(R.string.why_ads),
        TRACKING(R.string.why_tracking),
        DEMO(R.string.why_demo),
        COMMERCIAL(R.string.why_commercial),
        SUPPORT(R.string.why_support),
        VENDOR_MEDIA(R.string.why_vendor_media),
        BROADCAST(R.string.why_broadcast),
        VOICE_EXTRAS(R.string.why_voice_extras),
        SCREENSAVER(R.string.why_screensaver),
        GOOGLE_EXTRAS(R.string.why_google_extras),
        ACCESSIBILITY(R.string.why_accessibility),
        SYSTEM_EXTRAS(R.string.why_system_extras),
        SETUP(R.string.why_setup),
        AUTOINSTALL(R.string.why_autoinstall),
        CASTING(R.string.why_casting),
    }

    /** Why an app that looks removable is better kept: the greyed tile's help text. */
    enum class Keep(val text: Int) {
        CASTING(R.string.keep_casting),
        MEDIA(R.string.keep_media),
        VOICE(R.string.keep_voice),
        ACCESSIBILITY(R.string.keep_accessibility),
        UPDATES(R.string.keep_updates),
        NEEDED(R.string.keep_needed),
        ACCOUNT(R.string.keep_account),
        HOME(R.string.keep_home),
        DISPUTED(R.string.keep_disputed),
    }

    class Entry(val pkg: String, val level: Level, val why: Why)

    /**
     * [removable] ordered safe, then ask-first, then expert; [kept] the installed apps better kept,
     * with why; [protectedCount] the installed protected apps.
     */
    class Scan(val removable: List<Entry>, val kept: List<Pair<String, Keep>>, val protectedCount: Int)

    fun scan(installed: Collection<String>): Scan = Scan(
        entries.filter { it.pkg in installed && removable(it.pkg) }.sortedBy { it.level.ordinal },
        installed.mapNotNull { pkg -> keepReason(pkg)?.let { pkg to it } }.sortedBy { it.first },
        installed.count(::isProtected),
    )

    /** An entry Couch may remove: on this brand's list, and neither protected nor better kept. */
    fun entry(pkg: String): Entry? = entries.firstOrNull { it.pkg == pkg && removable(pkg) }

    private fun removable(pkg: String) = !isProtected(pkg) && keepReason(pkg) == null

    fun isProtected(pkg: String) = pkg in catalog.protected

    fun keepReason(pkg: String): Keep? = if (isProtected(pkg)) null else catalog.keep[pkg]

    /** Every package the catalog names, protected ones included: what the scan looks up. */
    val known: Set<String> get() = catalog.known

    companion object {
        /** The brand's own list first, then Google's extras; a package named twice keeps the brand's entry. */
        fun forManufacturer(catalog: Catalog, manufacturer: String): DebloatList {
            val m = manufacturer.lowercase()
            val brand = catalog.lists.firstOrNull { (names, _) -> names.isNotEmpty() && names.any(m::startsWith) }?.second.orEmpty()
            val common = catalog.lists.firstOrNull { (names, _) -> names.isEmpty() }?.second.orEmpty()
            return DebloatList(catalog, (brand + common).distinctBy { it.pkg })
        }
    }
}
