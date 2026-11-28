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
import cloud.samlo.couch.index.AppSheet
import cloud.samlo.couch.settings.Dialogs
import cloud.samlo.couch.ui.Card
import cloud.samlo.couch.ui.Popover
import kotlin.concurrent.thread

/**
 * The long-press menu. An app's is a sheet over the Index's right column ([AppSheet]): arrange tiles
 * (pin, move, folder, hide), a Manage list (rename, force stop, clear cache, app info) and Uninstall
 * alone at the foot; the folder list is a second page of the same sheet. A program card's menu is a
 * small popover beside the card, opening its app or that app's page, and a folder row has its own, to
 * rename the folder or remove it. Inputs are named, given icons and hidden in settings.
 */
class CardMenus(
    private val activity: Activity,
    private val config: () -> Config,
    private val files: UserFiles,
    private val apps: () -> List<Tile>,
    /** Lifts the app out of the Index list so up and down move it: the dialog-free Move. */
    private val onMove: (Tile) -> Unit,
    /** Shows a sheet over the Index's column, or turns the open one to this page; focus returns to the view. */
    private val showSheet: (AppSheet.Spec, View) -> Unit,
    private val closeSheet: () -> Unit,
    /** The app's screen time this week, as the list writes it, or null. */
    private val week: (String) -> String?,
    private val reload: () -> Unit,
) {
    /** The open menu, so a second long-press replaces it instead of stacking windows. */
    private var popover: Popover? = null

    fun show(card: Card) = show(card.tile, card)

    fun show(tile: Tile, anchor: View) {
        if (tile.kind == Tile.Kind.APP) {
            popover?.dismiss()
            showSheet(appSheet(tile, anchor), anchor)
            return
        }
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
        closeSheet()
    }

    /** [anchor] is the view the menu points at: a field opens beside it, not over the screen. */
    private fun itemsFor(tile: Tile, anchor: View): List<Popover.Item>? = when (tile.kind) {
        Tile.Kind.APP, Tile.Kind.INPUT -> null
        Tile.Kind.PROGRAM -> tile.packageName?.let { pkg ->
            val app = AppLabels.of(activity, pkg) ?: pkg
            listOf(
                Popover.Item(R.drawable.ic_menu_open, activity.getString(R.string.menu_open_app, app)) { openApp(pkg) },
                Popover.Item(R.drawable.ic_menu_info, activity.getString(R.string.menu_app_info)) { appInfo(pkg) },
            )
        }
    }

    /**
     * The app's sheet: what it is (icon, name, this week and its folder), the arrange tiles, then what
     * manages it, with Uninstall alone below a rule. [focus] names the tile to start on, for the page
     * Back returns to from the folder list.
     */
    private fun appSheet(tile: Tile, anchor: View, focus: String? = null): AppSheet.Spec {
        val pkg = tile.packageName
        val pinned = tile.key in config().pinnedApps
        val folder = config().folders.firstOrNull { tile.key in it.apps }
        val adb = adbEnabled()
        val tiles = mutableListOf<Popover.Item>()
        tiles += Popover.Item(
            R.drawable.ic_menu_pin,
            activity.getString(if (pinned) R.string.menu_unpin else R.string.menu_pin),
        ) { pin(tile) }
        // An app in a folder is not a row of the list, so there is no place to move it to.
        if (folder == null && apps().count { (it.key in config().pinnedApps) == pinned } > 1) {
            tiles += Popover.Item(R.drawable.ic_menu_move, activity.getString(R.string.menu_move)) { onMove(tile) }
        }
        val folderLabel = activity.getString(if (folder == null) R.string.menu_add_to_folder else R.string.menu_remove_from_folder)
        tiles += Popover.Item(R.drawable.ic_menu_folder, folderLabel) {
            if (folder == null) chooseFolder(tile, anchor) else removeFromFolder(tile)
        }
        tiles += Popover.Item(R.drawable.ic_menu_hide, activity.getString(R.string.menu_hide)) { hide(tile) }
        val manage = mutableListOf<Popover.Item>()
        manage += Popover.Item(R.drawable.ic_menu_rename, activity.getString(R.string.menu_rename)) { rename(tile, anchor) }
        // Force stop and a cache wipe go through the TV's own adbd, so they need debugging on.
        if (adb && pkg != null) {
            manage += Popover.Item(R.drawable.ic_menu_stop, activity.getString(R.string.menu_force_stop)) {
                shell(pkg, R.string.menu_stopped) { "am force-stop $it" }
            }
            manage += Popover.Item(R.drawable.ic_menu_clear, activity.getString(R.string.menu_clear_cache), hint = cacheSize(pkg)) {
                shell(pkg, R.string.menu_cache_cleared) { "pm clear --cache-only $it" }
            }
        }
        manage += Popover.Item(R.drawable.ic_menu_info, activity.getString(R.string.menu_app_info)) { appInfo(pkg) }
        val sections = mutableListOf(AppSheet.Section(activity.getString(R.string.sheet_manage), manage))
        if (pkg != null && removable(pkg)) {
            sections += AppSheet.Section(
                null,
                listOf(Popover.Item(
                    R.drawable.ic_menu_uninstall, activity.getString(R.string.menu_uninstall),
                    hint = activity.getString(R.string.sheet_uninstall_note), warn = true,
                ) { uninstall(pkg) }),
                divided = true,
            )
        }
        val about = listOfNotNull(
            week(tile.key)?.let { activity.getString(R.string.sheet_week, it) },
            folder?.let { activity.getString(R.string.sheet_in_folder, it.name) },
        )
        return AppSheet.Spec(
            name = tile.label,
            subtitle = about.joinToString(" · "),
            icon = AppSheet.icon(activity, pkg, MENU_ICON_DP),
            iconDp = MENU_ICON_DP,
            tiles = tiles,
            sections = sections,
            focus = focus,
        )
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
     * The folders an app can join, as a second page of the app's sheet, with New folder at the end. A pick
     * or a new name closes the sheet; Back returns to the app's own page, on its Folder tile.
     */
    private fun chooseFolder(tile: Tile, anchor: View) {
        val config = config()
        val label = activity.getString(R.string.menu_add_to_folder)
        val items = config.folders.map { folder ->
            Popover.Item(R.drawable.ic_menu_folder, folder.name) { addToFolder(tile, folder.id) }
        }.toMutableList()
        items += Popover.Item(R.drawable.ic_menu_plus, activity.getString(R.string.menu_new_folder)) { newFolder(tile, anchor) }
        showSheet(
            AppSheet.Spec(
                name = tile.label,
                subtitle = label,
                icon = AppSheet.icon(activity, tile.packageName, MENU_ICON_DP),
                iconDp = MENU_ICON_DP,
                sections = listOf(AppSheet.Section(null, items)),
                up = { appSheet(tile, anchor, focus = label) },
            ),
            anchor,
        )
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

        /** The app's icon in the long-press sheet's header. */
        const val MENU_ICON_DP = 56
    }
}
