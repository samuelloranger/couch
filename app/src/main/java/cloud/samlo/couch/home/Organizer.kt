package cloud.samlo.couch.home

import android.app.Activity
import android.view.KeyEvent
import cloud.samlo.couch.R
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.Sections
import cloud.samlo.couch.data.Tile
import cloud.samlo.couch.data.UserFiles
import cloud.samlo.couch.ui.Card
import cloud.samlo.couch.ui.FocusRow
import cloud.samlo.couch.ui.Hero
import cloud.samlo.couch.ui.Rows
import cloud.samlo.couch.ui.glideToNewPlaces

/**
 * Reorder mode for the apps row: OK picks an app up and puts it down, left/right moves it,
 * Back leaves. Focus stays in the apps row the whole time.
 */
class Organizer(
    private val activity: Activity,
    private val rows: Rows,
    private val hero: Hero,
    private val config: () -> Config,
    private val files: UserFiles,
    private val reload: () -> Unit,
) {
    var active = false
        private set
    private var movingCard: Card? = null

    fun start(pick: Card? = null) {
        val row = rows.section(Sections.APPS)?.row ?: return
        active = true
        // Move from Recent moves the app in the apps row, whose order is the one saved.
        val card = pick?.let { p -> row.cards().firstOrNull { it.tile.key == p.tile.key } }
        if (card != null) card.requestFocus() else row.restoreFocus()
        if (card != null) startMoving(card) else hint()
    }

    fun stop() {
        movingCard?.let { stopMoving(it) }
        active = false
        (activity.currentFocus as? Card)?.let { hero.show(it.tile) }
        reload()
    }

    fun hint() = hero.showHint(
        activity.getString(R.string.organize_title),
        activity.getString(if (movingCard != null) R.string.organize_moving else R.string.organize_idle),
    )

    /** True when the key was handled here; false hands it back to the activity. */
    fun consumesKey(event: KeyEvent): Boolean {
        if (!active) return false
        val up = event.action == KeyEvent.ACTION_UP
        val card = movingCard
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> if (up) stop()
            // Stay in the apps row.
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> Unit
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (card == null) return false
                if (event.action == KeyEvent.ACTION_DOWN) {
                    shift(card, if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (up) confirm(card)
            else -> return false
        }
        return true
    }

    /** OK: put the held app down, or pick up the focused one. */
    private fun confirm(held: Card?) {
        val focused = activity.currentFocus as? Card
        when {
            held != null -> stopMoving(held)
            focused != null && focused.tile.kind == Tile.Kind.APP -> startMoving(focused)
        }
    }

    private fun startMoving(card: Card) {
        movingCard = card
        card.moving = true
        hint()
    }

    private fun stopMoving(card: Card) {
        movingCard = null
        card.moving = false
        val section = rows.section(Sections.APPS) ?: return
        config().pinnedApps = section.row.cards().map { it.tile.key }
        config().save(files.dir)
        // Forces the row to rebuild from the saved order on the next load.
        section.signature = ""
        if (active) hint()
    }

    private fun shift(card: Card, by: Int) {
        val row = (card.parent?.parent as? FocusRow) ?: return
        val strip = row.strip
        val from = strip.indexOfChild(card)
        val to = from + by
        if (to < 0 || to >= strip.childCount) return
        // Move the neighbour rather than the card, so the focused card never detaches.
        val neighbour = strip.getChildAt(to)
        glideToNewPlaces(card, neighbour)
        strip.removeView(neighbour)
        strip.addView(neighbour, from)
        row.fixMargins()
        card.post { row.reveal(card) }
    }
}
