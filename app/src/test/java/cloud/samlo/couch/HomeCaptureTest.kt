package cloud.samlo.couch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCaptureTest {
    private fun home(pkg: String, cls: String) = HomeAccessibilityService.isStockHome(pkg, cls)

    @Test
    fun replacesWholeAndroidTvLaunchers() {
        assertTrue(home("com.google.android.tvlauncher", "com.google.android.tvlauncher.MainActivity"))
        assertTrue(home("com.google.android.leanbacklauncher", "android.widget.FrameLayout"))
    }

    @Test
    fun replacesOnlyGoogleTvHomeScreen() {
        assertTrue(home("com.google.android.apps.tv.launcherx", "com.google.android.apps.tv.launcherx.home.HomeActivity"))
        assertFalse(home("com.google.android.apps.tv.launcherx", "com.google.android.apps.tv.launcherx.profile.chooser.ProfileChooserActivity"))
        assertFalse(home("com.google.android.apps.tv.launcherx", "android.widget.FrameLayout"))
    }

    @Test
    fun replacesOnlyFireTvHomeScreen() {
        assertTrue(home("com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.HomeActivity_vNext"))
        assertFalse(home("com.amazon.tv.launcher", "com.amazon.tv.launcher.settings.SettingsActivity"))
    }
}
