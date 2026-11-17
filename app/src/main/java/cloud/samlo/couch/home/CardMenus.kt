package cloud.samlo.couch.home

import android.app.Activity
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
import cloud.samlo.couch.data.Folder
import cloud.samlo.couch.data.Folders
import cloud.samlo.couch.data.Tile
import cloud.samlo.couch.data.UserFiles
import cloud.samlo.couch.data.startSafely
import cloud.samlo.couch.settings.Dialogs
import cloud.samlo.couch.ui.Card
import cloud.samlo.couch.ui.Popover
import kotlin.concurrent.thread

/**
 * The long-press menu on an app or a program card: a small popover beside the pressed card.
 * An app can be pinned, moved, renamed, put in or taken out of a folder, hidden, force stopped,
 * cleared, uninstalled or opened in Android's app info; a program opens its app or that same page.
 * A folder row has its own menu, to rename the folder or remove it. Inputs are named, given icons
 * and hidden in settings.
 */
class CardMenus(
    private val activity: Activity,
    private val config: () -> Config,
    private val files: UserFiles,
    private val apps: () -> List<Tile>,
    /** Lifts the app out of the Index list so up and down move it: the dialog-free Move. */
    private val onMove: (Tile) -> Unit,
    private val reload: () -> Unit,
) {
    /** The open menu, so a second long-press replaces it instead of stacking windows. */
    private var popover: Popover? = null

    fun show(card: Card) = show(card.tile, card)

    fun show(tile: Tile, anchor: View) {
        val items = itemsFor(tile, anchor) ?: return
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

    /** [anchor] is the view the menu points at: a field opens beside it, not over the screen. */
    private fun itemsFor(tile: Tile, anchor: View): List<Popover.Item>? = when (tile.kind) {
        Tile.Kind.APP -> appItems(tile, anchor)
        Tile.Kind.INPUT -> null
        Tile.Kind.PROGRAM -> tile.packageName?.let { pkg ->
            val app = AppLabels.of(activity, pkg) ?: pkg
            listOf(
                Popover.Item(R.drawable.ic_menu_open, activity.getString(R.string.menu_open_app, app)) { openApp(pkg) },
                Popover.Item(R.drawable.ic_menu_info, activity.getString(R.string.menu_app_info)) { appInfo(pkg) },
            )
        }
    }

    private fun appItems(tile: Tile, anchor: View): List<Popover.Item> {
        val pkg = tile.packageName
        val pinned = tile.key in config().pinnedApps
        val folder = config().folders.firstOrNull { tile.key in it.apps }
        val adb = adbEnabled()
        val items = mutableListOf<Popover.Item>()
        items += Popover.Item(
            R.drawable.ic_menu_pin,
            activity.getString(if (pinned) R.string.menu_unpin else R.string.menu_pin),
        ) { pin(tile) }
        // An app in a folder is not a row of the list, so there is no place to move it to.
        if (folder == null && apps().count { (it.key in config().pinnedApps) == pinned } > 1) {
            items += Popover.Item(R.drawable.ic_menu_move, activity.getString(R.string.menu_move)) { onMove(tile) }
        }
        items += Popover.Item(R.drawable.ic_menu_rename, activity.getString(R.string.menu_rename)) { rename(tile, anchor) }
        items += Popover.Item(
            R.drawable.ic_menu_folder,
            activity.getString(if (folder == null) R.string.menu_add_to_folder else R.string.menu_remove_from_folder),
            hint = if (folder == null) ARROW else null,
        ) {
            if (folder == null) chooseFolder(tile, anchor) else removeFromFolder(tile)
        }
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

    private fun rename(tile: Tile, anchor: View) {
        val config = config()
        Dialogs.text(activity, anchor, config.names[tile.key] ?: tile.label, tile.label) { name ->
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

    /**
     * The folders an app can join, beside the app's own menu, with New folder at the end. A pick or a
     * new name opens its own popover; leaving with Left or Back comes back to the app's menu.
     */
    private fun chooseFolder(tile: Tile, anchor: View) {
        val config = config()
        var chose = false
        val items = config.folders.map { folder ->
            Popover.Item(R.drawable.ic_menu_folder, folder.name) {
                chose = true
                addToFolder(tile, folder.id)
            }
        }.toMutableList()
        items += Popover.Item(R.drawable.ic_menu_plus, activity.getString(R.string.menu_new_folder), divider = config.folders.isNotEmpty()) {
            chose = true
            newFolder(tile, anchor)
        }
        popover?.dismiss()
        popover = Popover(activity, anchor, items).apply {
            setOnDismissListener {
                if (popover === this) popover = null
                // The dismiss runs before a row's own action: let it set chose before deciding.
                if (!chose) anchor.post { if (anchor.isAttachedToWindow) show(tile, anchor) }
            }
            show()
        }
    }

    /** Puts the app in a new folder named after OK, which the TV's keyboard or the phone types. */
    private fun newFolder(tile: Tile, anchor: View) {
        val name = activity.getString(R.string.folder_default_name)
        Dialogs.text(activity, anchor, name, activity.getString(R.string.folder_name_hint)) { typed ->
            val config = config()
            val folder = Folder(Folders.newId(config.folders.map { it.id }), typed.ifEmpty { name }, listOf(tile.key))
            config.folders = config.folders + folder
            config.save(files.dir)
            reload()
        }
    }

    /** Puts the app in [id]'s folder, taking it out of whichever folder held it: an app is in one. */
    private fun addToFolder(tile: Tile, id: String) {
        val config = config()
        config.folders = config.folders.map { folder ->
            when {
                folder.id == id -> Folder(folder.id, folder.name, folder.apps + tile.key)
                tile.key in folder.apps -> Folder(folder.id, folder.name, folder.apps - tile.key)
                else -> folder
            }
        }
        config.save(files.dir)
        reload()
    }

    private fun removeFromFolder(tile: Tile) {
        val config = config()
        config.folders = config.folders.map { folder ->
            if (tile.key in folder.apps) Folder(folder.id, folder.name, folder.apps - tile.key) else folder
        }
        config.save(files.dir)
        reload()
    }

    /** The folder row's own menu: its name, or the folder gone and its apps back in the list. */
    fun showFolder(id: String, name: String, anchor: View) {
        val items = listOf(
            Popover.Item(R.drawable.ic_menu_rename, activity.getString(R.string.menu_rename)) { renameFolder(id, name, anchor) },
            Popover.Item(R.drawable.ic_menu_trash, activity.getString(R.string.menu_remove_folder), warn = true, divider = true) { removeFolder(id) },
        )
        popover?.dismiss()
        popover = Popover(activity, anchor, items).apply {
            setOnDismissListener { if (popover === this) popover = null }
            show()
        }
    }

    private fun renameFolder(id: String, current: String, anchor: View) {
        Dialogs.text(activity, anchor, current, current) { typed ->
            val config = config()
            config.folders = config.folders.map { if (it.id == id) Folder(it.id, typed.ifEmpty { current }, it.apps) else it }
            config.save(files.dir)
            reload()
        }
    }

    /** Forgets the folder only: its apps become rows of the list again. */
    private fun removeFolder(id: String) {
        val config = config()
        config.folders = config.folders.filterNot { it.id == id }
        config.save(files.dir)
        reload()
    }

    private fun openApp(pkg: String) {
        activity.packageManager.getLeanbackLaunchIntentForPackage(pkg)?.let {
            activity.startSafely(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun appInfo(pkg: String?) {
        pkg ?: return
        activity.startSafely(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Android's own uninstall question; it confirms, so a mis-press costs one more screen. */
    private fun uninstall(pkg: String) {
        if (!SAFE_PACKAGE.matches(pkg)) return
        activity.startSafely(
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

        /** The hint on a row that opens another popover. */
        const val ARROW = "›"
    }
}
