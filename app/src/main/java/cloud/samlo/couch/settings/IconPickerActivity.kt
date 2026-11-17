package cloud.samlo.couch.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import cloud.samlo.couch.R
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.Fetcher
import cloud.samlo.couch.data.Images
import cloud.samlo.couch.data.InputIcon
import cloud.samlo.couch.data.Tile
import cloud.samlo.couch.data.UserFiles
import cloud.samlo.couch.ui.Card
import cloud.samlo.couch.ui.Tokens
import cloud.samlo.couch.ui.dp

/**
 * Pick an input's icon from real cards, each drawn exactly as it will look on the home screen.
 * "Automatic" shows what the name would guess.
 */
class IconPickerActivity : Activity() {
    private val files by lazy { UserFiles(this) }
    private val images by lazy { Images(this, Fetcher(this)) }

    override fun onDestroy() {
        images.shutdown()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val config = Config.load(files.dir)
        Tokens.applyAccent(config.accent)
        val key = intent.getStringExtra(EXTRA_KEY) ?: return finish()
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()

        val current = InputIcon.of(config.inputIcons[key])
        var focusTarget: Card? = null
        val grid = grid()
        InputIcon.entries.chunked(COLUMNS).forEach { chunk ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
            }
            chunk.forEachIndexed { i, icon ->
                val card = card(icon, name) { pick(config, key, icon) }
                (card.layoutParams as LinearLayout.LayoutParams).apply {
                    if (i > 0) marginStart = dp(Tokens.CARD_GAP) - dp(Tokens.RING_INSET) * 2
                    bottomMargin = dp(6)
                }
                row.addView(card)
                if (icon == current) focusTarget = card
            }
            grid.addView(row)
        }

        val root = screen(name, grid)
        setContentView(root)
        root.post { (focusTarget ?: grid.findFocus())?.requestFocus() }
    }

    /** Saves the choice ("auto" clears it) and returns to the list. */
    private fun pick(config: Config, key: String, icon: InputIcon) {
        config.inputIcons = if (icon == InputIcon.AUTO) config.inputIcons - key else config.inputIcons + (key to icon.id)
        config.save(files.dir)
        setResult(RESULT_OK)
        finish()
    }

    private fun card(icon: InputIcon, inputName: String, onPick: () -> Unit): Card {
        val shown = if (icon == InputIcon.AUTO) InputIcon.resolve(null, inputName) else icon
        val tile = Tile(
            key = icon.id,
            kind = Tile.Kind.INPUT,
            label = getString(icon.label),
            intent = Intent(),
            subtitle = if (icon == InputIcon.AUTO) getString(shown.label) else null,
            iconRes = shown.res,
            cardColor = shown.color?.let { Color.parseColor(it) },
            iconWide = shown.wide,
        )
        return Card(this, tile, CARD_W, CARD_H, images, showLabel = false, look = "") {}
            .apply { setOnClickListener { onPick() } }
    }

    private fun grid() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(Tokens.OVERSCAN_H) - dp(Tokens.RING_INSET), dp(8), 0, dp(80))
        clipChildren = false
        clipToPadding = false
    }

    private fun screen(inputName: String, grid: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(Tokens.OVERSCAN_V + 10), 0, 0)
        addView(TextView(context).apply {
            text = getString(R.string.picker_title, inputName)
            textSize = 30f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Tokens.textPrimary)
            setPadding(dp(Tokens.OVERSCAN_H), 0, 0, 0)
        })
        addView(TextView(context).apply {
            text = getString(R.string.picker_hint)
            textSize = 15f
            setTextColor(Tokens.textMuted)
            setPadding(dp(Tokens.OVERSCAN_H), dp(4), 0, dp(16))
        })
        addView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            addView(grid)
        }, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    companion object {
        private const val EXTRA_KEY = "key"
        private const val EXTRA_NAME = "name"
        private const val COLUMNS = 5
        private const val CARD_W = 150
        private const val CARD_H = 84

        fun intent(context: Context, key: String, name: String) =
            Intent(context, IconPickerActivity::class.java).putExtra(EXTRA_KEY, key).putExtra(EXTRA_NAME, name)
    }
}
