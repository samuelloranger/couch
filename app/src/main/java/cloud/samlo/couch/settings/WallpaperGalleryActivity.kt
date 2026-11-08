package cloud.samlo.couch.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import cloud.samlo.couch.R
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.Fetcher
import cloud.samlo.couch.data.Images
import cloud.samlo.couch.data.Tile
import cloud.samlo.couch.data.UserFiles
import cloud.samlo.couch.data.WallpaperImages
import cloud.samlo.couch.data.WallpaperLibrary
import cloud.samlo.couch.data.WallpaperUpload
import cloud.samlo.couch.phone.HomeReload
import cloud.samlo.couch.phone.Phones
import cloud.samlo.couch.ui.Card
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/** Remote-friendly gallery using the same cards, focus rings and image pipeline as the launcher. */
class WallpaperGalleryActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val files by lazy { UserFiles(this) }
    private val images by lazy { Images(this, Fetcher(this)) }
    private lateinit var grid: LinearLayout
    private lateinit var hint: TextView
    private var adding = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Tokens.applyAccent(Config.load(files.dir).accent)
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        hint = TextView(this).apply {
            setText(R.string.gallery_help); textSize = 15f; setTextColor(Tokens.textMuted)
            setPadding(0, dp(8), 0, dp(16))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0F0E.toInt())
            setPadding(dp(Tokens.OVERSCAN_H), dp(Tokens.OVERSCAN_V), dp(Tokens.OVERSCAN_H), 0)
            addView(TextView(this@WallpaperGalleryActivity).apply {
                setText(R.string.gallery_title); textSize = 28f; setTextColor(Tokens.textPrimary)
            })
            addView(LinearLayout(this@WallpaperGalleryActivity).apply {
                addView(action(R.string.gallery_add) { pick() })
                addView(action(R.string.gallery_use) {
                    Config.load(files.dir).apply { wallpaper = "custom" }.save(files.dir)
                    Phones.configChanged(Config.load(files.dir))
                    toast(R.string.gallery_selected)
                })
                addView(action(R.string.gallery_refresh) { load() })
            })
            addView(hint)
            addView(ScrollView(this@WallpaperGalleryActivity).apply {
                clipChildren = false; isVerticalScrollBarEnabled = false; addView(grid)
            }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        HomeReload.listener = { runOnUiThread { load() } }
        load()
    }

    override fun onPause() { HomeReload.listener = null; super.onPause() }
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }

    private fun action(label: Int, click: () -> Unit) = Button(this).apply {
        setText(label); textSize = 15f; isAllCaps = false; setOnClickListener { click() }
    }

    private fun load() {
        worker.execute {
            val entries = runCatching { WallpaperLibrary(checkNotNull(files.dir)).entries() }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                grid.removeAllViews()
                hint.setText(if (entries == null) R.string.gallery_failed else if (entries.isEmpty()) R.string.gallery_empty else R.string.gallery_help)
                entries.orEmpty().chunked(3).forEach { chunk ->
                    val row = LinearLayout(this).apply { clipChildren = false }
                    chunk.forEach { entry ->
                        val tile = Tile(entry.id, Tile.Kind.PROGRAM, entry.file.name, Intent(), imageUrl = entry.file.path)
                        val card = Card(this, tile, 260, 146, images, true, "gallery") {}
                        card.setOnClickListener { preview(entry) }
                        row.addView(card)
                    }
                    grid.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(16) })
                }
            }
        }
    }

    private fun preview(entry: WallpaperLibrary.Entry) {
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            minimumHeight = dp(280); contentDescription = entry.file.name
        }
        val dialog = AlertDialog.Builder(this).setTitle(entry.file.name).setView(image)
            .setNegativeButton(R.string.gallery_close, null)
            .setPositiveButton(R.string.gallery_delete) { _, _ -> confirmDelete(entry) }.create()
        val alive = java.util.concurrent.atomic.AtomicBoolean(true)
        dialog.setOnDismissListener { alive.set(false) }
        dialog.show()
        images.load(entry.file.path, 1280, 720, 0f, isCurrent = { alive.get() }) { image.setImageBitmap(it) }
    }

    private fun confirmDelete(entry: WallpaperLibrary.Entry) {
        AlertDialog.Builder(this).setTitle(R.string.gallery_delete_confirm).setMessage(entry.file.name)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.gallery_delete) { _, _ ->
                worker.execute {
                    val ok = runCatching { WallpaperLibrary(checkNotNull(files.dir)).delete(entry.id) }.isSuccess
                    runOnUiThread { if (ok) changed() else toast(R.string.gallery_failed) }
                }
            }.show()
    }

    private fun pick() {
        if (adding) return
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE)
            }, PICK)
        } catch (e: ActivityNotFoundException) { toast(R.string.gallery_phone_import) }
    }

    @Deprecated("Activity result API used without an AndroidX dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        adding = true
        hint.setText(R.string.gallery_importing)
        worker.execute {
            val ok = runCatching {
                // Some document providers do not know the length: bound the stream ourselves.
                val bytes = checkNotNull(contentResolver.openInputStream(uri)).use { input ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(WallpaperLibrary.CHUNK_BYTES)
                    while (true) {
                        val count = input.read(chunk)
                        if (count < 0) break
                        require(buffer.size() + count <= WallpaperLibrary.MAX_BYTES)
                        buffer.write(chunk, 0, count)
                    }
                    buffer.toByteArray()
                }
                WallpaperUpload().use { upload ->
                    val id = UUID.randomUUID().toString()
                    upload.begin(checkNotNull(files.dir), id, "Photo", bytes.size)
                    var offset = 0
                    while (offset < bytes.size) {
                        val end = minOf(offset + WallpaperLibrary.CHUNK_BYTES, bytes.size)
                        upload.append(id, offset, bytes.copyOfRange(offset, end)); offset = end
                    }
                    upload.finish(id) { file -> WallpaperImages.format(file)?.takeIf { WallpaperImages.jpeg(file, false) != null } }
                }
            }.isSuccess
            runOnUiThread {
                adding = false
                if (!isFinishing && !isDestroyed) { if (ok) changed() else { toast(R.string.gallery_failed); load() } }
            }
        }
    }

    private fun changed() {
        Phones.broadcast(JSONObject().put("t", "wallpapers.changed"))
        if (!isDestroyed && !isFinishing) load()
    }

    private fun toast(label: Int) = Toast.makeText(this, label, Toast.LENGTH_LONG).show()
    private companion object { const val PICK = 801 }
}
