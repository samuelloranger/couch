package cloud.samlo.couch.index

import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import cloud.samlo.couch.R
import cloud.samlo.couch.data.AppLabels
import cloud.samlo.couch.data.Images
import cloud.samlo.couch.data.Tile
import cloud.samlo.couch.ui.FlatView
import cloud.samlo.couch.ui.Fonts
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.TvField
import cloud.samlo.couch.ui.dp
import cloud.samlo.couch.ui.dpf
import cloud.samlo.couch.ui.reveal

/**
 * Search in the app list's box: the field on top, then the panel's own rows (voice, the TV's
 * search), the apps and folders whose names match, and Continue watching titles. Matching happens
 * on the labels Index already holds, so a keystroke runs no query; typing comes from the phone app
 * or a keyboard, rather than a letter grid pressed with the remote.
 *
 * It opens on the field. Down reaches the first result, up from it comes back here, and left
 * leaves for the rail from anywhere.
 */
@SuppressLint("ViewConstructor")
class SearchPanel(
    context: Context,
    private val images: Images,
    /** Left from the panel: the rail picks the item level with the focused view. */
    private val onLeave: (View) -> Unit,
    /** A result has focus: the right column shows its app, and its program when it has one. */
    private val onPreview: (IndexEntry, Tile?) -> Unit,
    /** A folder result is opened: the list comes back with it selected and focus moves into its apps. */
    private val onFolder: (IndexEntry) -> Unit,
) : FrameLayout(context) {
    private val field = TvField(context).apply {
        typeface = Fonts.hanken(context, 400)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(Tokens.textPrimary)
        setHintTextColor(IndexTokens.railIdle)
        setHint(R.string.search_hint)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_NONE
        isSingleLine = true
        isCursorVisible = true
        // The hint is long for this box: an ellipsis reads better than a word cut in half.
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        background = null
        minHeight = 0
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, 0, 0, 0)
        // The phone types into the field, so its text must never be offered to a password manager.
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }
    private val fieldBox = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            cornerRadius = context.dpf(FIELD_RADIUS)
            setColor(Tokens.surface)
            setStroke(context.dpf(FIELD_STROKE).toInt(), IndexTokens.barOff)
        }
    }
    private val body = FrameLayout(context).apply { clipChildren = true }
    private val track = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val rows = mutableListOf<SearchRow>()
    /** The row whose look says "selected"; the field can hold focus while it stays lit. */
    private var chosen: SearchRow? = null
    /** Where focus was last, so Right from the rail comes back into the panel where it left. */
    private var last: View = field
    private var entries: List<IndexEntry> = emptyList()
    private var watchNext: List<Tile> = emptyList()
    /** False while the app list has the box: keystrokes and binds then do nothing. */
    private var shown = false

    init {
        val glyph = ImageView(context).apply {
            setImageResource(R.drawable.ic_rail_search)
            setColorFilter(Tokens.textMuted)
        }
        fieldBox.addView(glyph, LayoutParams(context.dp(FIELD_ICON), context.dp(FIELD_ICON), Gravity.CENTER_VERTICAL).apply {
            marginStart = context.dp(FIELD_PAD)
        })
        fieldBox.addView(field, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            marginStart = context.dp(FIELD_PAD + FIELD_ICON + FIELD_GAP)
            marginEnd = context.dp(FIELD_PAD)
        })
        place(fieldBox, 0, 0, IndexTokens.LIST_W, FIELD_H)
        place(body, 0, BODY_Y, IndexTokens.LIST_W, IndexTokens.LIST_H - BODY_Y)
        body.addView(track, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (shown) render()
            }
        })
        field.setOnFocusChangeListener { v, has ->
            paintField(has)
            if (has) last = v
        }
    }

    /** The apps and programs the panel searches, in the list's own order. */
    fun bind(entries: List<IndexEntry>, watchNext: List<Tile>) {
        this.entries = entries
        this.watchNext = watchNext
        if (shown) render()
    }

    /**
     * The rows keep their full height inside the list's box, like a scroll view's content, so the
     * box clips them and the rows glide under the field. A WrapContent track would be capped at the
     * box's height, and its last rows would draw nowhere.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        track.measure(
            MeasureSpec.makeMeasureSpec(body.measuredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
    }

    /** Takes the box and puts the cursor in the field. */
    fun open() {
        shown = true
        visibility = VISIBLE
        render()
        reveal()
        field.requestFocus()
    }

    /** Gives the box back: the next open starts on an empty query. */
    fun close() {
        // HOME or the search key can close the panel under the keyboard, which would stay up alone.
        field.hideKeyboard()
        shown = false
        field.setText("")
        chosen = null
        visibility = GONE
    }

    /**
     * Where focus goes when it comes back from the right column or the rail: the row that had it
     * (a reload rebuilds the rows, so a stale one falls back to the field).
     */
    fun target(): View = last.takeIf { it.parent != null } ?: chosen?.takeIf { it.parent != null } ?: field

    /** The app the focused row opens; null for the panel's own rows. */
    fun entryOf(view: View): IndexEntry? = (view as? SearchRow)?.entry

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!shown) return super.dispatchKeyEvent(event)
        val focused = findFocus()
        // The phone sends text, not a D-pad press: typing lands in the field even from a result.
        if (event.action == KeyEvent.ACTION_DOWN && !field.hasFocus() && isTyping(event)) {
            field.requestFocus()
            return field.dispatchKeyEvent(event)
        }
        return when (event.keyCode) {
            // The field would move its cursor instead: Up and Down always walk the results.
            KeyEvent.KEYCODE_DPAD_UP -> move(event, focused, down = false)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(event, focused, down = true)
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onLeave(focused ?: this)
                true
            }
            else -> super.dispatchKeyEvent(event)
        }
    }

    /** Text as a keyboard or the phone's field sends it: the OK keys open results and are left alone. */
    private fun isTyping(event: KeyEvent): Boolean {
        val text = event.unicodeChar != 0 || event.keyCode == KeyEvent.KEYCODE_DEL || event.keyCode == KeyEvent.KEYCODE_FORWARD_DEL
        val confirms = event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        return text && !confirms && !event.isCtrlPressed
    }

    /** One step through the rows, from the field or another row; never wraps, never leaves. */
    private fun move(event: KeyEvent, focused: View?, down: Boolean): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val i = rows.indexOfFirst { it === focused }
            val target = when {
                i < 0 -> if (down) rows.firstOrNull() else null
                down -> rows.getOrNull(i + 1) ?: rows[i]
                else -> rows.getOrNull(i - 1) ?: field
            }
            target?.requestFocus()
        }
        // The key's other half is swallowed too: a press moves one row, never two.
        return true
    }

    /** Rebuilds the rows for the current query: the panel holds few, so a keystroke just re-lists them. */
    private fun render() {
        val query = field.text.toString().trim()
        val hadRowFocus = (last as? SearchRow)?.hasFocus() == true
        val lastKey = (last as? SearchRow)?.key
        track.removeAllViews()
        // A new list starts at its top; a glide from the last query's results would land oddly.
        track.animate().cancel()
        track.translationY = 0f
        rows.clear()
        chosen = null

        add(SearchRow(context, null, null, context.getString(R.string.search_voice), "").apply {
            setIconDrawable(context.getDrawable(R.drawable.ic_mic))
            setOnClickListener { voice() }
        })
        if (query.isNotEmpty()) {
            val ask = context.getString(R.string.search_tv_for, query)
            add(SearchRow(context, null, null, ask, "").apply {
                setIconDrawable(context.getDrawable(R.drawable.ic_rail_search))
                setOnClickListener { searchTv(query) }
            })
        }

        if (query.isEmpty()) {
            section(R.string.section_apps)
            entries.filterNot { it.continues }.take(HOME_APPS).forEach { add(appRow(it, query)) }
        } else {
            val apps = entries.filterNot { it.continues }
                .mapNotNull { e -> SearchMatch.rank(e.app.label, query)?.let { e to it } }
                // sortedBy is stable: apps that rank alike keep the list's order.
                .sortedBy { it.second }
            if (apps.isNotEmpty()) {
                section(R.string.section_apps)
                apps.forEach { add(appRow(it.first, query)) }
            }
            val continuing = watchNext.filter { SearchMatch.find(it.label, query) != null }.take(CONTINUE_MAX)
            if (continuing.isNotEmpty()) {
                section(R.string.continue_watching)
                continuing.forEach { add(continueRow(it, query)) }
            }
            if (apps.isEmpty() && continuing.isEmpty()) nothing()
        }

        // A reload while a row had focus keeps it on the same result, or on the first one left.
        if (hadRowFocus) {
            val again = rows.firstOrNull { it.key == lastKey } ?: rows.firstOrNull()
            (again ?: field).requestFocus()
        }
    }

    private fun add(row: SearchRow) {
        rows += row
        row.setOnFocusChangeListener { v, has ->
            if (has) {
                last = v
                choose(v as SearchRow)
            }
        }
        track.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, context.dp(IndexTokens.LIST_ROW_H)).apply {
            bottomMargin = context.dp(IndexTokens.LIST_ROW_PITCH - IndexTokens.LIST_ROW_H)
        })
    }

    /** Marks [row] as the panel's selection and shows what it opens on the right. */
    private fun choose(row: SearchRow) {
        chosen = row
        rows.forEach { it.chosen = it === row }
        ensureVisible(row)
        row.entry?.let { onPreview(it, row.program) }
    }

    /** Glides the rows so [row] stays in the box; they fit most of the time, so nothing moves. */
    private fun ensureVisible(row: View) {
        val viewport = body.height
        // Measured past the box, the track's last row carries the content height.
        val content = track.getChildAt(track.childCount - 1)?.bottom ?: 0
        val top = row.top + track.translationY
        if (viewport <= 0 || content <= viewport) {
            track.translationY = 0f
            return
        }
        val target = when {
            top < 0 -> track.translationY - top
            top + row.height > viewport -> track.translationY - (top + row.height - viewport)
            else -> track.translationY
        }.coerceIn((viewport - content).toFloat(), 0f)
        if (target == track.translationY) return
        track.animate().translationY(target).setDuration(Tokens.GLIDE_MS).setInterpolator(Tokens.ease).start()
    }

    private fun appRow(entry: IndexEntry, query: String): SearchRow {
        // A folder says how many apps it holds, where an app shows its screen time.
        val meta = if (entry.isFolder) {
            context.resources.getQuantityString(R.plurals.folder_apps, entry.folderApps.size, entry.folderApps.size)
        } else {
            entry.usage?.let { Formats.short(context, it.weekMs) }.orEmpty()
        }
        return SearchRow(context, entry, null, highlight(entry.app.label, query), meta).apply {
            setIconBitmap(entry.icon)
            // A folder opens to its apps rather than launching one: it has no app of its own.
            setOnClickListener { if (entry.isFolder) onFolder(entry) else entry.app.launch(context) }
        }
    }

    private fun continueRow(program: Tile, query: String): SearchRow {
        val app = program.packageName?.let { AppLabels.of(context, it) }.orEmpty()
        return SearchRow(context, entries.firstOrNull { it.key == program.packageName }, program, highlight(program.label, query), app).apply {
            // Art like a card's, at row size; without any, a plain accent square.
            val url = program.imageUrl
            if (url == null) {
                fillIcon((Tokens.accent and 0x00FFFFFF) or 0x33000000)
            } else {
                val size = context.dp(IndexTokens.LIST_ICON)
                images.load(url, size, size, context.dpf(AppRow.ICON_RADIUS), isCurrent = { alive }) { setIconBitmap(it) }
            }
            setOnClickListener { program.launch(context) }
        }
    }

    private fun section(label: Int) {
        val text = IndexText(context, 8.5f, 600, IndexTokens.hours).apply {
            this.text = context.getString(label).uppercase()
            letterSpacing = 0.07f
        }
        track.addView(text, text.stack(
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = context.dp(ROW_PAD)
            },
            top = context.dp(if (track.childCount == 0) 8 else 13),
            bottom = context.dp(5),
        ))
    }

    private fun nothing() {
        val line = IndexText(context, 11f, 400, IndexTokens.description).apply { setText(R.string.search_nothing) }
        track.addView(line, line.stack(
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = context.dp(ROW_PAD)
            },
            top = context.dp(12),
        ))
    }

    /** [label] with the part [query] matched in the accent colour, so a row says why it is there. */
    private fun highlight(label: String, query: String): CharSequence {
        val match = SearchMatch.find(label, query) ?: return label
        return SpannableString(label).apply {
            setSpan(ForegroundColorSpan(Tokens.accent), match.start, match.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun paintField(focused: Boolean) {
        (fieldBox.background as? GradientDrawable)?.setStroke(
            context.dpf(FIELD_STROKE).toInt(),
            if (focused) Tokens.accent else IndexTokens.barOff,
        )
    }

    /** The system assistant, the same button as the remote's mic. */
    private fun voice() {
        if (!launch(Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) noVoice()
    }

    /** The TV's own search (Google TV, Fire OS); Couch has no catalogue of its own to search. */
    private fun searchTv(query: String) {
        val global = Intent(SearchManager.INTENT_ACTION_GLOBAL_SEARCH)
            .putExtra(SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val web = Intent(Intent.ACTION_WEB_SEARCH)
            .putExtra(SearchManager.QUERY, query)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!launch(global) && !launch(web)) noVoice()
    }

    private fun launch(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun noVoice() =
        Toast.makeText(context, R.string.search_voice_unavailable, Toast.LENGTH_SHORT).show()

    private fun place(view: View, x: Int, y: Int, w: Int, h: Int) {
        addView(view, LayoutParams(context.dp(w), context.dp(h)).apply {
            leftMargin = context.dp(x)
            topMargin = context.dp(y)
        })
    }

    /**
     * One result: the icon slot, a label whose match is in the accent, and the app's screen time
     * or the program's app on the right. Styled as [AppRow] is, with its own accent marker.
     */
    private class SearchRow(
        context: Context,
        /** The app this row opens, for the right column; null for voice and the TV's search. */
        val entry: IndexEntry?,
        /** The Continue watching program this row opens, when it has one. */
        val program: Tile?,
        text: CharSequence,
        meta: String,
    ) : FrameLayout(context) {
        /** Which result this row stands for across a reload. */
        val key: String? = entry?.key ?: program?.key
        /** Read from the image worker: art must not land on a row that is gone. */
        @Volatile
        var alive = true
            private set
        private val fill = GradientDrawable().apply { cornerRadius = context.dpf(6f) }
        private val marker = FlatView(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = context.dpf(1f)
                setColor(Tokens.accent)
            }
            visibility = INVISIBLE
        }
        private val icon = ImageView(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = context.dpf(AppRow.ICON_RADIUS)
                setColor(Tokens.surface)
            }
        }
        private val label = IndexText(context, 14f, 500, Tokens.textMuted).apply {
            letterSpacing = -0.01f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            this.text = text
        }
        private val hours = IndexText(context, 8.5f, 500, IndexTokens.hours).apply {
            fontFeatureSettings = "tnum"
            maxLines = 1
            this.text = meta
        }

        /** The selected look; only the colours change, so a move never triggers a layout pass. */
        var chosen = false
            set(value) {
                if (field == value) return
                field = value
                fill.setColor(if (value) SELECTED else Color.TRANSPARENT)
                marker.visibility = if (value) VISIBLE else INVISIBLE
                label.setTextColor(if (value) Tokens.textPrimary else Tokens.textMuted)
            }

        init {
            isFocusable = true
            isClickable = true
            contentDescription = text.toString()
            background = fill
            addView(marker, LayoutParams(context.dp(IndexTokens.LIST_MARKER_W), context.dp(IndexTokens.LIST_MARKER_H), Gravity.CENTER_VERTICAL))
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(context.dp(11), 0, context.dp(10), 0)
                addView(icon, LinearLayout.LayoutParams(context.dp(IndexTokens.LIST_ICON), context.dp(IndexTokens.LIST_ICON)))
                addView(label, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(9) })
                addView(hours, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = context.dp(6) })
            }
            addView(line, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }

        fun setIconBitmap(bitmap: Bitmap?) {
            if (bitmap == null) return
            // The slot's whole square, unlike a glyph: an app's art has no margin of its own.
            icon.setPadding(0, 0, 0, 0)
            icon.setColorFilter(null)
            icon.setImageBitmap(bitmap)
        }

        /**
         * A glyph in the icon slot: the slot's square less [GLYPH_INSET], so the mic and the
         * magnifier are drawn at one size and in one tone instead of each at its artwork's own.
         */
        fun setIconDrawable(drawable: Drawable?) {
            val inset = context.dp(GLYPH_INSET)
            icon.setPadding(inset, inset, inset, inset)
            icon.setImageDrawable(drawable)
            icon.setColorFilter(GLYPH_TINT)
        }

        /** A plain colour in the icon slot, for a program with no art. */
        fun fillIcon(color: Int) {
            (icon.background as? GradientDrawable)?.setColor(color)
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            alive = true
        }

        override fun onDetachedFromWindow() {
            alive = false
            super.onDetachedFromWindow()
        }
    }

    private companion object {
        const val FIELD_H = 38
        const val FIELD_RADIUS = 19f
        const val FIELD_STROKE = 1.5f
        const val FIELD_ICON = 14
        const val FIELD_PAD = 14
        const val FIELD_GAP = 9
        /** The field, then a breath before the results. */
        const val BODY_Y = FIELD_H + 8
        const val ROW_PAD = 11
        /** The empty query's short list, a start rather than the whole index. */
        const val HOME_APPS = 5
        const val CONTINUE_MAX = 3
        /** White at 6%, as the list's selected row. */
        const val SELECTED = 0x0FFFFFFF
        /** A row glyph keeps this much of its 22dp slot all round, so every glyph is drawn at one size. */
        const val GLYPH_INSET = 4
        /** One tone for the panel's glyphs, as for the rows' labels. */
        val GLYPH_TINT = Tokens.textMuted
    }
}
