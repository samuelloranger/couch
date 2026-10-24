package cloud.samlo.couch

import cloud.samlo.couch.debloat.Catalog
import cloud.samlo.couch.debloat.DebloatList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The scan: brand lists order themselves, nothing off the list is offered, protected apps never are. */
class DebloatListTest {
    private val catalog = Catalog(File("src/main/assets/debloat/lists.txt").readText())

    private fun brand(name: String) = DebloatList.forManufacturer(catalog, name)

    private val installed = listOf(
        "com.google.android.feedback",
        "com.sony.dtv.promos",
        "com.sony.dtv.seconddispsetting",
        "com.sony.dtv.smartmediaapp",
        "com.google.android.webview",
        "com.sony.dtv.tvx",
        "com.example.unknownnoise",
    )

    @Test fun safeFirstThenAskThenExpert() {
        val scan = brand("Sony").scan(installed)
        assertEquals(
            listOf("com.google.android.feedback", "com.sony.dtv.promos", "com.sony.dtv.seconddispsetting"),
            scan.removable.map { it.pkg },
        )
        assertEquals(listOf(DebloatList.Level.SAFE, DebloatList.Level.ASK, DebloatList.Level.EXPERT), scan.removable.map { it.level })
    }

    @Test fun protectedAppsAreCountedNeverOffered() {
        val scan = brand("Sony").scan(installed)
        assertEquals(2, scan.protectedCount)
        assertTrue(scan.removable.none { brand("Sony").isProtected(it.pkg) })
    }

    @Test fun theBootCriticalPackagesAreProtected() {
        listOf(
            "com.google.android.webview", "com.google.android.tvrecommendations", "com.google.android.katniss",
            "com.google.android.tts", "com.android.location.fused", "com.sony.dtv.tvx",
            "com.google.android.gms", "com.android.vending", "com.amazon.tv.launcher",
        ).forEach { assertTrue(it, brand("Sony").isProtected(it)) }
    }

    @Test fun onlyListedUnprotectedPackagesHaveAnEntry() {
        val sony = brand("Sony")
        assertNull(sony.entry("com.example.unknownnoise"))
        assertNull(sony.entry("com.sony.dtv.tvx"))
        assertNull(sony.entry("com.amazon.tv.acr")) // another brand's package
        assertEquals(DebloatList.Level.ASK, sony.entry("com.sony.dtv.promos")?.level)
    }

    /** One guide alone never makes an app "safe": every Sony entry comes from a single list. */
    @Test fun safeNeedsTwoSources() {
        val scan = brand("Sony").scan(catalog.known)
        assertTrue(scan.removable.filter { it.pkg.startsWith("com.sony.") }.none { it.level == DebloatList.Level.SAFE })
    }

    @Test fun appsPeopleRegretAreShownKeptNeverOffered() {
        val sony = brand("Sony")
        val scan = sony.scan(installed + "com.google.android.youtube.tv")
        assertEquals(
            listOf("com.google.android.youtube.tv" to DebloatList.Keep.NEEDED, "com.sony.dtv.smartmediaapp" to DebloatList.Keep.MEDIA),
            scan.kept,
        )
        assertNull(sony.entry("com.sony.dtv.smartmediaapp"))
        assertTrue(scan.removable.none { it.pkg == "com.google.android.youtube.tv" })
    }

    @Test fun unknownBrandsGetGooglesExtrasOnly() {
        val scan = brand("Acme").scan(installed)
        assertEquals(listOf("com.google.android.feedback"), scan.removable.map { it.pkg })
    }

    @Test fun otherBrandsMatchByManufacturerAndKeepTheirInputsProtected() {
        val scan = brand("TCL").scan(listOf("com.tcl.exhibit", "com.tcl.tvinput", "com.sony.dtv.promos"))
        assertEquals(listOf("com.tcl.exhibit"), scan.removable.map { it.pkg })
        assertTrue(brand("Sony").isProtected("com.tcl.tvinput"))
        assertEquals(1, scan.protectedCount)
    }

    @Test fun fireOsHasItsOwnList() {
        val scan = brand("Amazon").scan(listOf("com.amazon.tv.acr", "com.amazon.ftv.screensaver", "com.amazon.tv.launcher"))
        assertEquals(listOf("com.amazon.tv.acr", "com.amazon.ftv.screensaver"), scan.removable.map { it.pkg })
        assertEquals(1, scan.protectedCount)
    }

    /** Android 11+ hides undeclared packages; without this the scan would come back empty. */
    @Test fun theManifestCanSeeEveryPackage() {
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains("android.permission.QUERY_ALL_PACKAGES"))
    }

    /** Every line of the asset parses, and every kind and reason in it exists. */
    @Test fun theCatalogParsesWhole() {
        assertTrue(catalog.protected.size > 100)
        assertTrue(catalog.keep.size > 50)
        assertTrue(catalog.lists.first().first.isEmpty()) // Google's extras come first
        assertTrue(catalog.lists.size >= 10)
    }
}
