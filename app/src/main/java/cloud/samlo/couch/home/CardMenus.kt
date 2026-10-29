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
import cloud.samlo.couch.ui.Card

/**
 * The long-press menu on an app or a program card, and the actions it offers: rename, hide, app
 * info, or open the program's app. Inputs are named, given icons and hidden in settings.
 */
class CardMenus(
    private val activity: Activity,
    private val config: () -> Config,
    private val files: UserFiles,
    private val reload: () -> Unit,
) {
    fun show(card: Card) = show(card.tile)

    fun show(tile: Tile) {
        val actions = actionsFor(tile) ?: return
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(tile.label)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }

    private fun actionsFor(tile: Tile): List<Pair<String, () -> Unit>>? = when (tile.kind) {
        Tile.Kind.APP -> listOf(
            activity.getString(R.string.menu_rename) to { rename(tile) },
            activity.getString(R.string.menu_hide) to { hide(tile) },
            activity.getString(R.string.menu_app_info) to { appInfo(tile.packageName) },
        )
        Tile.Kind.INPUT -> null
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

    private fun hide(tile: Tile) {
        val config = config()
        config.hiddenApps = config.hiddenApps + tile.key
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
