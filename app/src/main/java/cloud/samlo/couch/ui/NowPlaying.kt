package cloud.samlo.couch.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import cloud.samlo.couch.MediaListener
import cloud.samlo.couch.R

/**
 * A pill in the top bar with what's playing (Spotify, YouTube Music...). OK plays or pauses,
 * a long press opens the app. Hidden when nothing plays or access isn't granted.
 */
@SuppressLint("ViewConstructor")
class NowPlaying(context: Context) : LinearLayout(context) {
    private val main = Handler(Looper.getMainLooper())
    private val component = ComponentName(context, MediaListener::class.java)
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val art = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true }
    private val title = text(14f, Tokens.textPrimary)
    private val artist = text(12f, Tokens.textMuted)
    private var controller: MediaController? = null
    private val watched = HashMap<MediaController, MediaController.Callback>()

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { watch(it.orEmpty()) }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        isLongClickable = true
        visibility = View.GONE
        setPadding(context.dp(6), context.dp(6), context.dp(16), context.dp(6))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), pill(0x40FFFFFF, Tokens.ring))
            addState(intArrayOf(), pill(0x26FFFFFF, null))
        }
        art.background = GradientDrawable().apply { cornerRadius = context.dpf(8f) }
        art.outlineProvider = ViewOutlineProvider.BACKGROUND
        addView(art, LayoutParams(context.dp(40), context.dp(40)))
        val column = LinearLayout(context).apply { orientation = VERTICAL }
        column.addView(title)
        column.addView(artist)
        addView(column, LayoutParams(context.dp(220), LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(10) })

        setOnClickListener { togglePlayback() }
        setOnLongClickListener {
            openApp()
            true
        }
    }

    fun start() {
        val manager = sessions ?: return
        try {
            manager.addOnActiveSessionsChangedListener(sessionsChanged, component, main)
            watch(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            // Notification access not granted: stay hidden.
            visibility = View.GONE
        }
    }

    fun stop() {
        try {
            sessions?.removeOnActiveSessionsChangedListener(sessionsChanged)
        } catch (e: Exception) {
            // Never registered.
        }
        unwatchAll()
    }

    private fun togglePlayback() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    private fun openApp() {
        try {
            controller?.sessionActivity?.send()
        } catch (e: Exception) {
            // The app went away; nothing to open.
        }
    }

    private fun unwatchAll() {
        watched.forEach { (c, cb) -> c.unregisterCallback(cb) }
        watched.clear()
    }

    private fun watch(controllers: List<MediaController>) {
        unwatchAll()
        for (c in controllers) {
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = pick()
                override fun onMetadataChanged(metadata: MediaMetadata?) = pick()
            }
            c.registerCallback(cb, main)
            watched[c] = cb
        }
        pick()
    }

    /** Prefer what is playing; else the first paused session that still has a title. */
    private fun pick() {
        val all = watched.keys
        val chosen = all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED && it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) != null }
        controller = chosen
        val meta = chosen?.metadata
        val name = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (chosen == null || name == null) {
            if (hasFocus()) focusSearch(View.FOCUS_DOWN)?.requestFocus()
            fadeOut()
            return
        }
        title.text = name
        artist.text = listOfNotNull(
            meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            if (chosen.playbackState?.state == PlaybackState.STATE_PAUSED) context.getString(R.string.now_playing_paused) else null,
        ).joinToString("  ·  ")
        val bitmap: Bitmap? = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
        art.setImageBitmap(bitmap)
        art.visibility = if (bitmap != null) View.VISIBLE else View.GONE
        fadeIn()
    }

    private fun pill(color: Int, stroke: Int?) = GradientDrawable().apply {
        cornerRadius = context.dpf(26f)
        setColor(color)
        if (stroke != null) setStroke(context.dp(2), stroke)
    }

    private fun text(size: Float, color: Int) = TextView(context).apply {
        textSize = size
        setTextColor(color)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
}
