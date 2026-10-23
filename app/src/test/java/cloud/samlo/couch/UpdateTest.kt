package cloud.samlo.couch

import cloud.samlo.couch.data.Config
import cloud.samlo.couch.update.Release
import cloud.samlo.couch.update.Versions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateTest {
    @Test
    fun versionsCompareNumerically() {
        assertTrue(Versions.isNewer("0.10.0", "0.2.0"))
        assertTrue(Versions.isNewer("v0.3.0", "0.2.0"))
        assertTrue(Versions.isNewer("1.0", "0.9.9"))
        assertFalse(Versions.isNewer("0.2.0", "0.2.0"))
        assertFalse(Versions.isNewer("0.1.9", "0.2.0"))
    }

    @Test
    fun preReleasesAndJunkAreNeverNewer() {
        assertFalse(Versions.isNewer("0.3.0-beta", "0.2.0"))
        assertFalse(Versions.isNewer("latest", "0.2.0"))
        assertFalse(Versions.isNewer("0.3.0", ""))
        assertNull(Versions.parse("1.2.3.4"))
        assertNull(Versions.parse("+1.0"))
        assertNull(Versions.parse("1..2"))
        assertEquals(listOf(1, 2, 0), Versions.parse("v1.2"))
    }

    @Test
    fun releaseReadsTheStableApkAndItsChecksum() {
        val release = Release.fromGitHub(github("v0.3.0", listOf("couch-v0.3.0.apk", "couch-v0.3.0.apk.sha256", "couch.apk")))
        assertNotNull(release)
        assertEquals("0.3.0", release!!.version)
        assertEquals("https://example.test/couch.apk", release.apkUrl)
        assertEquals("https://example.test/couch-v0.3.0.apk.sha256", release.checksumUrl)
        assertEquals("0.3.0", Release.parse(release.serialize())?.version)
    }

    @Test
    fun releasesMissingAnAssetOrMarkedPreReleaseAreIgnored() {
        assertNull(Release.fromGitHub(github("v0.3.0", listOf("couch-v0.3.0.apk", "couch-v0.3.0.apk.sha256"))))
        assertNull(Release.fromGitHub(github("v0.3.0", listOf("couch.apk"))))
        assertNull(Release.fromGitHub(github("v0.3.0", listOf("couch.apk", "couch-v0.3.0.apk.sha256"), prerelease = true)))
        assertNull(Release.fromGitHub("not json"))
        assertNull(Release.fromGitHub(github("v0.3.0", listOf("couch.apk", "couch-v0.3.0.apk.sha256"), base = "http://example.test/")))
    }

    @Test
    fun updateChecksAreOnByDefaultAndCanBeTurnedOff() {
        assertTrue(Config().checkUpdates)
        assertFalse(Config.fromJson("""{"checkUpdates":false}""").checkUpdates)
        assertFalse(Config.fromJson(Config.fromJson("""{"checkUpdates":false}""").toJson()).checkUpdates)
    }

    /** The pre-release channel takes the newest usable release in GitHub's list, pre-release or not. */
    @Test
    fun preReleaseChannelTakesTheNewestUsable() {
        val pre = github("v0.6.0", listOf("couch.apk", "couch-v0.6.0.apk.sha256"), prerelease = true)
        val broken = github("v0.7.0", listOf("couch.apk")) // no checksum: skipped
        val stable = github("v0.5.0", listOf("couch.apk", "couch-v0.5.0.apk.sha256"))
        assertEquals("0.6.0", Release.newestOf("[$broken,$pre,$stable]")?.version)
        assertNull(Release.newestOf("not json"))
        assertNull(Release.fromGitHub(pre)) // the stable channel still refuses it
    }

    private fun github(tag: String, assets: List<String>, prerelease: Boolean = false, base: String = "https://example.test/"): String {
        val list = assets.joinToString(",") { """{"name":"$it","browser_download_url":"$base$it"}""" }
        return """{"tag_name":"$tag","draft":false,"prerelease":$prerelease,"assets":[$list]}"""
    }
}
