package cloud.samlo.couch.home

import android.app.Activity
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.Images
import cloud.samlo.couch.data.Tile
import cloud.samlo.couch.ui.Card
import cloud.samlo.couch.ui.Rows
import cloud.samlo.couch.ui.Section
import cloud.samlo.couch.ui.arrange

/**
 * Applies loaded [SectionData] to the [Rows]: unchanged sections and cards are kept, so focus
 * and scroll position survive a reload.
 */
class SectionBinder(
    private val activity: Activity,
    private val rows: Rows,
    private val images: Images,
    private val isOrganizing: () -> Boolean,
    private val onFocused: (Card) -> Unit,
    private val onLongPress: (Card) -> Unit,
) {
    /** Before the first rows exist Android parks focus on the gear; the first bind takes it back. */
    private var placedInitialFocus = false

    fun bind(data: List<SectionData>, config: Config) {
        if (isOrganizing()) return
        val existing = rows.sections().associateBy { it.id }
        val ordered = data.map { d ->
            val section = existing[d.id] ?: Section(activity, d.id)
            if (section.signature != d.signature) update(section, d, config)
            section
        }
        rows.column.arrange(ordered) { old, index ->
            val id = (old as Section).id
            (ordered.firstOrNull { it.id == id } ?: ordered.getOrNull(index.coerceAtMost(ordered.lastIndex)))
                ?.row?.restoreFocus()
        }
        placeFocus(ordered)
        // Sections above the focused one may have come or gone: glide back to the anchor.
        rows.post { rows.revealFocused() }
    }

    private fun update(section: Section, d: SectionData, config: Config) {
        section.title.text = d.title
        // Only cards whose data changed are replaced; focus stays where it was.
        val look = "${d.w}|${d.h}|${config.look}"
        section.row.update(d.tiles, look) { card(it, d.w, d.h, look, config) }
        section.signature = d.signature
    }

    private fun placeFocus(ordered: List<Section>) {
        if (!placedInitialFocus && ordered.isNotEmpty()) {
            placedInitialFocus = true
            ordered.first().row.focusFirst()
        } else if (activity.currentFocus == null) {
            ordered.firstOrNull()?.row?.focusFirst()
        }
    }

    private fun card(tile: Tile, w: Int, h: Int, look: String, config: Config) =
        Card(activity, tile, w, h, images, config.showLabels, look, onFocused = onFocused).apply {
            isLongClickable = true
            setOnLongClickListener {
                onLongPress(this)
                true
            }
        }
}
