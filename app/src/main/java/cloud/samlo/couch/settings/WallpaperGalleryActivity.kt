package cloud.samlo.couch.settings

import cloud.samlo.couch.ui.Fonts
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
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
import cloud.samlo.couch.index.IndexText
import cloud.samlo.couch.phone.HomeReload
import cloud.samlo.couch.phone.Phones
import cloud.samlo.couch.ui.Card
import cloud.samlo.couch.ui.Popover
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.Type
import cloud.samlo.couch.ui.dp
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Remote-friendly gallery using the same cards, focus rings and image pipeline as the launcher.
 * OK shows an image over the whole screen, a long press offers what else it can do, and deleting
 * asks for a second OK in the popover: no dialog boxes.
 */
class WallpaperGalleryActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val files by lazy { UserFiles(this) }
    private val images by lazy { Images(this, Fetcher(this)) }
    private lateinit var screen: FrameLayout
    private lateinit var grid: LinearLayout
    private lateinit var hint: TextView
    private var adding = false
    /** The full-screen image showing, the thumbnail it came from, and the thumbnail whose actions are open. */
    private var preview: FrameLayout? = null
    private var previewCard: View? = null
    private var actions: Popover? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Tokens.applyAccent(Config.load(files.dir).accent)
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        hint = TextView(this).apply {
            setText(R.string.gallery_help); textSize = Type.LABEL; typeface = Fonts.hanken(this@WallpaperGalleryActivity, 500); setTextColor(Tokens.textMuted)
            setPadding(0, dp(8), 0, dp(16))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0B0F0E.toInt())
            setPadding(dp(Tokens.OVERSCAN_H), dp(Tokens.OVERSCAN_V), dp(Tokens.OVERSCAN_H), 0)
            addView(TextView(this@WallpaperGalleryActivity).apply {
                setText(R.string.gallery_title); textSize = Type.TITLE; typeface = Fonts.hanken(this@WallpaperGalleryActivity, 600); setTextColor(Tokens.textPrimary)
            })
            addView(LinearLayout(this@WallpaperGalleryActivity).apply {
                addView(action(R.string.gallery_add) { pick() })
                addView(action(R.string.gallery_use) { use() })
                addView(action(R.string.gallery_refresh) { load() })
            })
            addView(hint)
            addView(ScrollView(this@WallpaperGalleryActivity).apply {
                clipChildren = false; isVerticalScrollBarEnabled = false; addView(grid)
            }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        // A plain frame over the whole screen, so a preview covers the gallery without a dialog.
        screen = FrameLayout(this).apply { addView(root, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) }
        setContentView(screen)
    }

    override fun onResume() {
        super.onResume()
        HomeReload.listener = { runOnUiThread { load() } }
        load()
    }

    override fun onPause() { HomeReload.listener = null; super.onPause() }
    override fun onDestroy() { worker.shutdown(); images.shutdown(); super.onDestroy() }

    // Opted out of predictive back in the manifest, so this legacy hook still sees Back.
    @SuppressLint("GestureBackNavigation")
    @Suppress("DEPRECATION")
    @Deprecated("A preview closes with Back; the gallery leaves the screen.")
    override fun onBackPressed() {
        if (preview != null) closePreview() else super.onBackPressed()
    }

    private fun action(label: Int, click: () -> Unit) = Button(this).apply {
        setText(label); textSize = Type.LABEL; typeface = Fonts.hanken(this@WallpaperGalleryActivity, 500); isAllCaps = false; setOnClickListener { click() }
    }

    /** Ambient mode takes the whole folder, so this picks the source, as the toolbar's button does. */
    private fun use() {
        Config.load(files.dir).apply { wallpaper = "custom" }.save(files.dir)
        Phones.configChanged(Config.load(files.dir))
        toast(R.string.gallery_selected)
    }

    private fun load() {
        if (isDestroyed) return
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
                        card.setOnClickListener { previewImage(entry, card) }
                        card.isLongClickable = true
                        card.setOnLongClickListener {
                            showActions(entry, card)
                            true
                        }
                        row.addView(card)
                    }
                    grid.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(16) })
                }
            }
        }
    }

    /** What a thumbnail can do, in a popover beside it: use it, see it full screen, or delete it. */
    private fun showActions(entry: WallpaperLibrary.Entry, card: View) {
        if (actions != null) return
        val items = listOf(
            Popover.Item(R.drawable.ic_menu_wallpaper, getString(R.string.gallery_use)) { use() },
            Popover.Item(R.drawable.ic_menu_fullscreen, getString(R.string.gallery_fullscreen)) { previewImage(entry, card) },
            // A delete is never one press away: the row asks again, in the warning colour.
            Popover.Item(
                R.drawable.ic_menu_trash,
                getString(R.string.gallery_delete),
                warn = true,
                confirm = getString(R.string.gallery_delete_again),
            ) { delete(entry) },
        )
        actions = Popover(this, card, items, GALLERY_PANEL_W).apply {
            setOnDismissListener { if (actions === this) actions = null }
            show()
        }
    }

    /**
     * The image over the whole screen, on black, its name in the corner: Back or OK closes it. It is
     * a view of this screen rather than a dialog, so the picture gets every pixel.
     */
    private fun previewImage(entry: WallpaperLibrary.Entry, card: View) {
        if (preview != null) return
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = entry.file.name
        }
        val name = IndexText(this, Type.CAPTION, 500, Tokens.textMuted).apply { text = entry.file.name }
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isClickable = true
            addView(image, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(name, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(Tokens.OVERSCAN_H)
                bottomMargin = dp(Tokens.OVERSCAN_V)
            })
            setOnClickListener { closePreview() }
        }
        screen.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        preview = overlay
        previewCard = card
        overlay.requestFocus()
        // The picture comes from the cards' own pipeline: decoded once, off the UI thread.
        worker.execute {
            val bitmap = runCatching {
                WallpaperImages.jpeg(entry.file, true)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }.getOrNull()
            runOnUiThread {
                if (preview === overlay && !isFinishing && !isDestroyed) image.setImageBitmap(bitmap)
                else bitmap?.recycle()
            }
        }
    }

    private fun closePreview() {
        val overlay = preview ?: return
        preview = null
        (overlay.parent as? FrameLayout)?.removeView(overlay)
        // The thumbnail takes the remote back, so up, down and OK keep working after a preview.
        previewCard?.requestFocus()
        previewCard = null
    }

    private fun delete(entry: WallpaperLibrary.Entry) {
        worker.execute {
            val ok = runCatching { WallpaperLibrary(checkNotNull(files.dir)).delete(entry.id) }.isSuccess
            runOnUiThread { if (ok) changed() else toast(R.string.gallery_failed) }
        }
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
    private companion object {
        const val PICK = 801
        /** Wide enough for the second-press delete warning, in either language. */
        const val GALLERY_PANEL_W = 260
    }
}
