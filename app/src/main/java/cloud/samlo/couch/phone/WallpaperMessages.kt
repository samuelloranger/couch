package cloud.samlo.couch.phone

import android.content.Context
import android.util.Base64
import cloud.samlo.couch.data.UserFiles
import cloud.samlo.couch.data.WallpaperImages
import cloud.samlo.couch.data.WallpaperLibrary
import org.json.JSONArray
import org.json.JSONObject

/** Called only by Messages' bounded, serial worker, over an authenticated phone session. */
class WallpaperMessages(private val context: Context) {
    fun handle(session: Session, t: String, message: JSONObject) {
        val dir = checkNotNull(UserFiles(context).dir)
        val lib = WallpaperLibrary(dir)
        val id = message.optString("id")
        when (t) {
            "wallpapers.list" -> session.send(JSONObject().put("t", "wallpapers").put("images", JSONArray().apply {
                lib.entries().forEach { put(JSONObject().put("id", it.id).put("name", it.file.name).put("bytes", it.file.length())) }
            }))
            "wallpapers.image" -> {
                val preview = message.optBoolean("preview")
                val bytes = WallpaperImages.jpeg(lib.resolve(id), preview)
                session.send(JSONObject().put("t", "wallpapers.image").put("id", id).put("preview", preview)
                    .apply { bytes?.let { put("jpeg", Base64.encodeToString(it, Base64.NO_WRAP)) } })
            }
            "wallpapers.delete" -> { lib.delete(id); changed() }
            "wallpapers.upload.begin" -> {
                session.wallpaperUpload.begin(dir, id, message.optString("name", "Photo"), message.getInt("bytes"))
                ack(session, id, 0)
            }
            "wallpapers.upload.chunk" -> {
                val encoded = message.getString("data")
                require(encoded.length <= (WallpaperLibrary.CHUNK_BYTES + 2) / 3 * 4)
                val offset = session.wallpaperUpload.append(id, message.getInt("offset"), Base64.decode(encoded, Base64.DEFAULT))
                ack(session, id, offset)
            }
            "wallpapers.upload.finish" -> {
                session.wallpaperUpload.finish(id) { file ->
                    val format = WallpaperImages.format(file)
                    if (format != null && WallpaperImages.jpeg(file, false) != null) format else null
                }
                session.send(JSONObject().put("t", "wallpapers.upload").put("id", id).put("done", true).put("offset", message.optInt("bytes")))
                changed()
            }
            "wallpapers.upload.cancel" -> session.wallpaperUpload.cancel()
        }
    }

    private fun ack(session: Session, id: String, offset: Int) =
        session.send(JSONObject().put("t", "wallpapers.upload").put("id", id).put("offset", offset).put("done", false))

    private fun changed() {
        HomeReload.listener?.invoke()
        Phones.broadcast(JSONObject().put("t", "wallpapers.changed"))
    }
}
