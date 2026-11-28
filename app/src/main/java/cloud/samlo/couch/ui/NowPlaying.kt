package cloud.samlo.couch.ui

import cloud.samlo.couch.index.IndexTokens
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import cloud.samlo.couch.MediaListener
import cloud.samlo.couch.R
import cloud.samlo.couch.data.AppLabels

/**
 * The Now Playing dock at the foot of the Index's right column: what's playing, how far along it
 * is, and previous, play or pause and next. It shows only while a session plays or is paused on a
 * title, fades in and out, and a long press anywhere on it opens the app.
 *
 * The buttons follow the session's own actions: a session that can't skip gets no skip button to
 * press. Progress ticks once a second, and only while something is playing.
 */
@SuppressLint("ViewConstructor")
class NowPlaying(context: Context) : LinearLayout(context) {
    private val main = Handler(Looper.getMainLooper())
    private val component = ComponentName(context, MediaListener::class.java)
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val art = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        // The title beside it says what plays; the cover adds nothing for a screen reader.
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val title = text(Type.LABEL, 600, Tokens.textPrimary)
    private val subtitle = text(Type.CAPTION, 400, Tokens.textMuted)
    private val progress = ProgressLine(context)
    private val time = text(Type.CAPTION, 400, TIME_TEXT).apply {
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        fontFeatureSettings = "tnum"
        minimumWidth = context.dp(TIME_W)
    }
    private val previous = TransportButton(context, R.drawable.ic_np_prev, R.string.now_playing_previous)
    private val playPause = TransportButton(context, R.drawable.ic_np_play, R.string.now_playing_play_pause)
    private val next = TransportButton(context, R.drawable.ic_np_next, R.string.now_playing_next)
    private var controller: MediaController? = null
    private val watched = HashMap<MediaController, MediaController.Callback>()

    /** Called when the dock hides while it holds the focus, so the Index can put focus back on the app list. */
    var onHide: (() -> Unit)? = null

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { watch(it.orEmpty()) }

    /** Repaints the elapsed time and the progress line, which move on their own while playing. */
    private val tick = object : Runnable {
        override fun run() {
            val c = controller ?: return
            if (visibility != VISIBLE) return
            showProgress(c)
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) main.postDelayed(this, TICK_MS)
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        val pad = context.dp(PAD)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            cornerRadius = context.dpf(RADIUS)
            setColor(DOCK)
            setStroke(context.dp(1), DOCK_EDGE)
        }
        art.background = GradientDrawable().apply { cornerRadius = context.dpf(ART_RADIUS) }
        art.outlineProvider = ViewOutlineProvider.BACKGROUND
        addView(art, LayoutParams(context.dp(ART), context.dp(ART)).apply { marginEnd = context.dp(GAP) })
        val column = LinearLayout(context).apply { orientation = VERTICAL }
        column.addView(title, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        column.addView(subtitle, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        column.addView(progress, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(LINE)).apply { topMargin = context.dp(LINE_GAP) })
        addView(column, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(time, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(GAP) })
        val buttons = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(previous, playPause, next).forEach {
            buttons.addView(it, LayoutParams(context.dp(BUTTON), context.dp(BUTTON)))
        }
        addView(buttons, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(GAP) })

        previous.setOnClickListener { controller?.transportControls?.skipToPrevious() }
        playPause.setOnClickListener { togglePlayback() }
        next.setOnClickListener { controller?.transportControls?.skipToNext() }
        val openTheApp = View.OnLongClickListener {
            openApp()
            true
        }
        setOnLongClickListener(openTheApp)
        listOf(previous, playPause, next).forEach { it.setOnLongClickListener(openTheApp) }
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
        main.removeCallbacks(tick)
    }

    /** Where Down from the cards lands: play or pause, which every session offers. */
    fun entry(): View? = playPause.takeIf { visibility == VISIBLE }

    /** The enabled button next to [button] left or right of it, or null at the row's end. */
    fun neighbour(button: View, direction: Int): View? {
        val enabled = listOf(previous, playPause, next).filter { it.isEnabled }
        val at = enabled.indexOf(button)
        if (at < 0) return null
        return enabled.getOrNull(at + if (direction == FOCUS_LEFT) -1 else 1)
    }

    // The ticks are for what the eye follows: nothing to refresh while the screen is elsewhere.
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        main.removeCallbacks(tick)
        if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING && visibility == VISIBLE) main.postDelayed(tick, TICK_MS)
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(tick)
        super.onDetachedFromWindow()
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
        val name = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
        if (chosen == null || meta == null || name == null) {
            hide()
            return
        }
        title.text = name
        subtitle.text = subtitle(chosen, meta)
        val bitmap: Bitmap? = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
        art.setImageBitmap(bitmap)
        art.visibility = if (bitmap != null) View.VISIBLE else View.GONE
        showTransport(chosen)
        showProgress(chosen)
        fadeIn()
        main.removeCallbacks(tick)
        if (chosen.playbackState?.state == PlaybackState.STATE_PLAYING) main.postDelayed(tick, TICK_MS)
    }

    /** "Artist · app", with the paused word when paused, as the top bar's line read. */
    private fun subtitle(c: MediaController, meta: MediaMetadata): String = listOfNotNull(
        meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
        AppLabels.of(context, c.packageName),
        if (c.playbackState?.state == PlaybackState.STATE_PAUSED) context.getString(R.string.now_playing_paused) else null,
    ).filter { it.isNotBlank() }.joinToString(" · ")

    /** The three buttons as the session allows: no skip offered, none shown as one. */
    private fun showTransport(c: MediaController) {
        val state = c.playbackState
        val actions = state?.actions ?: 0L
        previous.setTransportEnabled(actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
        next.setTransportEnabled(actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
        playPause.setIcon(if (state?.state == PlaybackState.STATE_PLAYING) R.drawable.ic_np_pause else R.drawable.ic_np_play)
        // The session may drop a skip it had; a button that can't be pressed can't keep the focus.
        val focused = focusedChild
        if (focused is TransportButton && !focused.isEnabled) playPause.requestFocus()
    }

    /** The elapsed time and the line, both hidden when the session reports no duration. */
    private fun showProgress(c: MediaController) {
        val state = c.playbackState
        val duration = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (duration <= 0L) {
            time.visibility = View.GONE
            progress.visibility = View.GONE
            return
        }
        val position = PlaybackProgress.position(
            state?.position ?: 0L,
            state?.lastPositionUpdateTime ?: 0L,
            state?.playbackSpeed ?: 1f,
            state?.state == PlaybackState.STATE_PLAYING,
            SystemClock.elapsedRealtime(),
        ).coerceAtMost(duration)
        time.text = context.getString(R.string.now_playing_time, PlaybackProgress.clock(position), PlaybackProgress.clock(duration))
        time.visibility = View.VISIBLE
        progress.visibility = View.VISIBLE
        progress.fraction = PlaybackProgress.fraction(position, duration)
    }

    private fun hide() {
        main.removeCallbacks(tick)
        if (hasFocus()) onHide?.invoke()
        fadeOut()
    }

    /** A one-line label at the design's line height, so the column's two lines stay 11.5 and 9dp tall. */
    private fun text(sizeSp: Float, weight: Int, color: Int) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = Fonts.hanken(context, weight)
        setTextColor(color)
        includeFontPadding = false
        isFallbackLineSpacing = false
        setLineHeight(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, resources.displayMetrics).toInt())
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private companion object {
        /** The dock's own box: 12dp corners, its contents inset by 8dp. */
        const val PAD = 8
        const val RADIUS = 12f
        const val ART = 36
        const val ART_RADIUS = 7f
        const val GAP = 11
        const val BUTTON = 34
        const val LINE = 2
        const val LINE_GAP = 5
        const val TIME_W = 52
        const val TICK_MS = 1_000L
    }
}

/** A round transport button: a filled circle with a glyph, or the accent ring while focused. */
@SuppressLint("ViewConstructor")
private class TransportButton(context: Context, glyphRes: Int, descriptionRes: Int) : FrameLayout(context) {
    private val glyph = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER }
    private var iconRes = 0

    init {
        isFocusable = true
        isClickable = true
        contentDescription = context.getString(descriptionRes)
        setIcon(glyphRes)
        addView(glyph, LayoutParams(context.dp(GLYPH_SIZE), context.dp(GLYPH_SIZE), Gravity.CENTER))
        paint(focused = false)
        setOnFocusChangeListener { _, hasFocus -> paint(hasFocus) }
    }

    /** The glyph, flipped between play and pause as the session changes. */
    fun setIcon(res: Int) {
        if (iconRes == res) return
        iconRes = res
        glyph.setImageResource(res)
    }

    /** Off when the session's actions lack it: dimmed, and skipped by the D-pad. */
    fun setTransportEnabled(on: Boolean) {
        isEnabled = on
        isFocusable = on
        alpha = if (on) 1f else DISABLED_ALPHA
    }

    private fun paint(focused: Boolean) {
        background = if (focused) ring() else null
        glyph.imageTintList = ColorStateList.valueOf(if (focused) GLYPH_FOCUSED else GLYPH_IDLE)
    }

    /** A 30dp fill inside a 2dp accent ring at this 34dp view's edge, so the circles keep 4dp apart. */
    private fun ring() = LayerDrawable(
        arrayOf(
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(context.dp(2), (Tokens.accent and 0x00FFFFFF) or 0x99000000.toInt())
            },
            GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Tokens.textPrimary)
            },
        ),
    ).apply {
        val inset = context.dp(2)
        setLayerInset(1, inset, inset, inset, inset)
    }

    private companion object {
        const val GLYPH_SIZE = 13
        /** The ring sits in the gutter; the visible circle is 30dp across. */
        const val DISABLED_ALPHA = 0.35f
    }
}

/** The dock's progress line: a track with the played part over it, both rounded. */
@SuppressLint("ViewConstructor")
private class ProgressLine(context: Context) : View(context) {
    private val track = Paint().apply {
        color = TRACK
        isAntiAlias = true
    }
    private val fill = Paint().apply {
        color = BAR
        isAntiAlias = true
    }
    private val height = context.dpf(2f)
    private val rect = RectF()

    /** How much of the track is played, 0..1. */
    var fraction = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val radius = height / 2
        val top = (getHeight() - height) / 2
        rect.set(0f, top, width.toFloat(), top + height)
        canvas.drawRoundRect(rect, radius, radius, track)
        rect.right = width * fraction
        canvas.drawRoundRect(rect, radius, radius, fill)
    }
}

/** The dock's greys, its own since the top bar's pills had theirs. */
private val DOCK = Color.parseColor("#151B19")
private val DOCK_EDGE = Color.parseColor("#1F2725")
private val TRACK = Color.parseColor("#2C3532")
private val BAR = Color.parseColor("#C4CCC9")
private val GLYPH_IDLE = Color.parseColor("#C4CCC9")
private val GLYPH_FOCUSED = Color.parseColor("#0A0D0C")
private val TIME_TEXT = IndexTokens.hours
