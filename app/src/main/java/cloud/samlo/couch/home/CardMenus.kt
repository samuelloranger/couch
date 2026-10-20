package cloud.samlo.couch.home

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import cloud.samlo.couch.R
import cloud.samlo.couch.data.AppLabels
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.Tile
import cloud.samlo.couch.data.UserFiles
import cloud.samlo.couch.settings.Dialogs
import cloud.samlo.couch.settings.IconPickerActivity
import cloud.samlo.couch.ui.Card

/** The long-press menu on a card, and the actions it offers: move, rename, icon, hide, app info. */
class CardMenus(
    private val activity: Activity,
    private val config: () -> Config,
    private val files: UserFiles,
    private val reload: () -> Unit,
    private val startMove: (Card) -> Unit,
) {
    fun show(card: Card) = show(card.tile, card)

    /** Without a [card] there is nothing to move, so the menu leaves Move out. */
    fun show(tile: Tile, card: Card? = null) {
        val actions = actionsFor(tile, card) ?: return
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(tile.label)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun actionsFor(tile: Tile, card: Card?): List<Pair<String, () -> Unit>>? = when (tile.kind) {
        Tile.Kind.APP -> listOfNotNull(
            card?.let { activity.getString(R.string.menu_move) to { startMove(it) } },
            activity.getString(R.string.menu_rename) to { rename(tile) },
            activity.getString(R.string.menu_hide) to { hide(tile) },
            activity.getString(R.string.menu_app_info) to { appInfo(tile.packageName) },
        )
        Tile.Kind.INPUT -> listOf(
            activity.getString(R.string.menu_rename) to { rename(tile) },
            activity.getString(R.string.menu_icon) to { pickIcon(tile) },
            activity.getString(R.string.menu_hide) to { hide(tile) },
        )
        Tile.Kind.PROGRAM -> tile.packageName?.let { pkg ->
            val app = AppLabels.of(activity, pkg) ?: pkg
            listOf(
                activity.getString(R.string.menu_open_app, app) to { openApp(pkg) },
                activity.getString(R.string.menu_app_info) to { appInfo(pkg) },
            )
        }
    }

    private fun rename(tile: Tile) {
        val config = config()
        Dialogs.text(activity, activity.getString(R.string.menu_rename), config.names[tile.key] ?: tile.label, tile.label) { name ->
            config.names = if (name.isEmpty()) config.names - tile.key else config.names + (tile.key to name)
            config.save(files.dir)
            reload()
        }
    }

    /** The picker saves the choice; coming back to home reloads the row. */
    private fun pickIcon(tile: Tile) {
        activity.startActivity(IconPickerActivity.intent(activity, tile.key, tile.label))
    }

    private fun hide(tile: Tile) {
        val config = config()
        when (tile.kind) {
            Tile.Kind.APP -> config.hiddenApps = config.hiddenApps + tile.key
            Tile.Kind.INPUT -> config.hiddenInputs = config.hiddenInputs + tile.key
            Tile.Kind.PROGRAM -> return
        }
        config.save(files.dir)
        reload()
    }

    private fun openApp(pkg: String) {
        activity.packageManager.getLeanbackLaunchIntentForPackage(pkg)?.let {
            activity.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun appInfo(pkg: String?) {
        pkg ?: return
        activity.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
