package cloud.samlo.couch.home

import android.app.Activity
import android.app.AlertDialog
import android.app.usage.StorageStatsManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.format.Formatter
import android.view.View
import android.widget.Toast
import cloud.samlo.couch.R
import cloud.samlo.couch.adb.AdbShell
import cloud.samlo.couch.adb.ShellOutput
import cloud.samlo.couch.data.AppLabels
import cloud.samlo.couch.data.AppOrder
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.Tile
import cloud.samlo.couch.data.UserFiles
import cloud.samlo.couch.settings.Dialogs
import cloud.samlo.couch.ui.Card
import cloud.samlo.couch.ui.Popover
import kotlin.concurrent.thread

/**
 * The long-press menu on an app or a program card: a small popover beside the pressed card.
 * An app can be pinned, moved, renamed, hidden, force stopped, cleared, uninstalled or opened in
 * Android's app info; a program opens its app or that same page. Inputs are named, given icons
 * and hidden in settings.
 */
class CardMenus(
    private val activity: Activity,
    private val config: () -> Config,
    private val files: UserFiles,
    private val apps: () -> List<Tile>,
    private val reload: () -> Unit,
) {
    /** The open menu, so a second long-press replaces it instead of stacking windows. */
    private var popover: Popover? = null

    fun show(card: Card) = show(card.tile, card)

    fun show(tile: Tile, anchor: View) {
        val items = itemsFor(tile) ?: return
        popover?.dismiss()
        popover = Popover(activity, anchor, items).apply {
            setOnDismissListener { if (popover === this) popover = null }
            show()
        }
    }

    /** Closes the open menu, if any: home was pressed, or the screen is going away. */
    fun dismiss() {
        popover?.dismiss()
    }

    private fun itemsFor(tile: Tile): List<Popover.Item>? = when (tile.kind) {
        Tile.Kind.APP -> appItems(tile)
        Tile.Kind.INPUT -> null
        Tile.Kind.PROGRAM -> tile.packageName?.let { pkg ->
            val app = AppLabels.of(activity, pkg) ?: pkg
            listOf(
                Popover.Item(R.drawable.ic_menu_open, activity.getString(R.string.menu_open_app, app)) { openApp(pkg) },
                Popover.Item(R.drawable.ic_menu_info, activity.getString(R.string.menu_app_info)) { appInfo(pkg) },
            )
        }
    }

    private fun appItems(tile: Tile): List<Popover.Item> {
        val pkg = tile.packageName
        val pinned = tile.key in config().pinnedApps
        val adb = adbEnabled()
        val items = mutableListOf<Popover.Item>()
        items += Popover.Item(
            R.drawable.ic_menu_pin,
            activity.getString(if (pinned) R.string.menu_unpin else R.string.menu_pin),
        ) { pin(tile) }
        if (apps().count { (it.key in config().pinnedApps) == pinned } > 1) {
            items += Popover.Item(R.drawable.ic_menu_move, activity.getString(R.string.menu_move)) { move(tile) }
        }
        items += Popover.Item(R.drawable.ic_menu_rename, activity.getString(R.string.menu_rename)) { rename(tile) }
        items += Popover.Item(R.drawable.ic_menu_hide, activity.getString(R.string.menu_hide)) { hide(tile) }
        // Force stop and a cache wipe go through the TV's own adbd, so they need debugging on.
        if (adb && pkg != null) {
            items += Popover.Item(R.drawable.ic_menu_stop, activity.getString(R.string.menu_force_stop), divider = true) {
                shell(pkg, R.string.menu_stopped) { "am force-stop $it" }
            }
            items += Popover.Item(R.drawable.ic_menu_trash, activity.getString(R.string.menu_clear_cache), hint = cacheSize(pkg)) {
                shell(pkg, R.string.menu_cache_cleared) { "pm clear --cache-only $it" }
            }
        }
        if (pkg != null && removable(pkg)) {
            items += Popover.Item(
                R.drawable.ic_menu_uninstall,
                activity.getString(R.string.menu_uninstall),
                warn = true,
                // With no shell rows above it, the uninstall row starts the risky group itself.
                divider = !adb,
            ) { uninstall(pkg) }
        }
        items += Popover.Item(R.drawable.ic_menu_info, activity.getString(R.string.menu_app_info)) { appInfo(pkg) }
        return items
    }

    private fun pin(tile: Tile) {
        val config = config()
        AppOrder.pin(config, tile.key)
        config.save(files.dir)
        reload()
    }

    private fun move(tile: Tile) {
        val config = config()
        val visible = apps()
        val pinned = tile.key in config.pinnedApps
        val group = visible.filter { (it.key in config.pinnedApps) == pinned }
        if (group.size < 2) return
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(activity.getString(R.string.move_app_title, tile.label))
            .setSingleChoiceItems(group.mapIndexed { i, app -> "${i + 1}. ${app.label}" }.toTypedArray(), group.indexOfFirst { it.key == tile.key }) { dialog, which ->
                AppOrder.move(config, visible.map { it.key }, tile.key, which)
                config.save(files.dir)
                dialog.dismiss()
                reload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    /** Android's own uninstall question; it confirms, so a mis-press costs one more screen. */
    private fun uninstall(pkg: String) {
        if (!SAFE_PACKAGE.matches(pkg)) return
        activity.startActivity(
            Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * Runs one shell command for [pkg] on a worker, then says how it went on the UI thread:
     * [done] when adbd answered with no complaint, a plain refusal when it couldn't be reached
     * or the command reported a failure.
     */
    private fun shell(pkg: String, done: Int, command: (String) -> String) {
        // The package is pasted into a shell line, so anything but a package name must not get through.
        if (!SAFE_PACKAGE.matches(pkg)) return
        val line = command(pkg)
        thread {
            // adbd sends the command's text, never its exit code, so an "Error:" in that text is
            // the only sign the command did nothing.
            val output = AdbShell.once(activity) { it.run(line) }
            val worked = output != null && !ShellOutput.refused(output)
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                val message = if (worked) done else R.string.menu_shell_failed
                Toast.makeText(activity, activity.getString(message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun adbEnabled() = Settings.Global.getInt(activity.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1

    /** The cache the system reports for [pkg], or null when it won't say (no usage access). */
    private fun cacheSize(pkg: String): String? = runCatching {
        val stats = activity.getSystemService(StorageStatsManager::class.java)
            .queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle())
        Formatter.formatShortFileSize(activity, stats.cacheBytes)
    }.getOrNull()

    /** System apps can't be uninstalled, but one updated from a store can; Couch never offers itself. */
    private fun removable(pkg: String): Boolean {
        if (pkg == activity.packageName) return false
        val flags = runCatching { activity.packageManager.getApplicationInfo(pkg, 0).flags }.getOrNull() ?: return false
        val system = flags and ApplicationInfo.FLAG_SYSTEM != 0
        val updated = flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        return !system || updated
    }

    private companion object {
        /** Package names, and nothing that could change a shell line's meaning. */
        val SAFE_PACKAGE = Regex("^[A-Za-z0-9._]+$")
    }
}
