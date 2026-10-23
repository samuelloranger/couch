package cloud.samlo.couch.update

import org.json.JSONObject

/** A published release: its version, its APK and the SHA-256 file that vouches for it. */
class Release(val version: String, val apkUrl: String, val checksumUrl: String) {
    /** Downloads only ever come over https. */
    private val secure: Boolean get() = apkUrl.startsWith("https://") && checksumUrl.startsWith("https://")

    fun serialize(): String =
        JSONObject().put("version", version).put("apk", apkUrl).put("sha256", checksumUrl).toString()

    companion object {
        /** The asset every release attaches under the same name; see the release workflow. */
        const val APK = "couch.apk"

        /** GitHub's release JSON, or null for drafts, pre-releases and releases missing an asset. */
        fun fromGitHub(text: String): Release? = try {
            fromJson(JSONObject(text), preReleases = false)
        } catch (e: Exception) {
            null
        }

        /**
         * The newest usable release in GitHub's release list (newest first), pre-releases included:
         * the pre-release channel. Drafts and releases without the APK are skipped.
         */
        fun newestOf(list: String): Release? = try {
            val array = org.json.JSONArray(list)
            (0 until array.length()).firstNotNullOfOrNull { fromJson(array.getJSONObject(it), preReleases = true) }
        } catch (e: Exception) {
            null
        }

        private fun fromJson(json: JSONObject, preReleases: Boolean): Release? {
            return try {
                if (json.optBoolean("draft") || (!preReleases && json.optBoolean("prerelease"))) return null
                val tag = json.getString("tag_name")
                val assets = json.getJSONArray("assets")
                val urls = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .associate { it.getString("name") to it.getString("browser_download_url") }
                val apk = urls[APK] ?: return null
                val checksum = urls["couch-$tag.apk.sha256"] ?: return null
                Release(tag.removePrefix("v"), apk, checksum).takeIf { it.secure }
            } catch (e: Exception) {
                null
            }
        }

        fun parse(text: String): Release? {
            return try {
                val json = JSONObject(text)
                Release(json.getString("version"), json.getString("apk"), json.getString("sha256")).takeIf { it.secure }
            } catch (e: Exception) {
                null
            }
        }
    }
}
