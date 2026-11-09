package cloud.samlo.couch

import cloud.samlo.couch.debloat.Catalog
import cloud.samlo.couch.debloat.DebloatList
import cloud.samlo.couch.debloat.Engine
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The check in front of `pm uninstall`: only a plain package name from the brand's list gets through. */
class EngineGuardTest {
    private val list = DebloatList.forManufacturer(Catalog(File("src/main/assets/debloat/lists.txt").readText()), "Sony")

    @Test fun listedPackageIsAllowed() = assertTrue(Engine.removable("com.sony.dtv.promos", list))

    @Test fun shellMetacharactersAreRefused() {
        assertFalse(Engine.removable("a;rm -rf /", list))
        assertFalse(Engine.removable("com.sony.dtv.promos; reboot", list))
    }

    @Test fun unlistedPackageIsRefused() {
        assertFalse(Engine.removable("com.example.unknownnoise", list))
        assertFalse(Engine.removable("com.sony.dtv.tvx", list))
    }

    @Test fun noListRefusesEverything() = assertFalse(Engine.removable("com.sony.dtv.promos", null))
}
