package cloud.samlo.couch.debloat

import android.app.ActivityManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import cloud.samlo.couch.R
import cloud.samlo.couch.settings.SettingsContext
import cloud.samlo.couch.settings.SettingsPage
import cloud.samlo.couch.ui.Tokens
import kotlin.concurrent.thread

/**
 * The debloat screens, one list rebuilt for each step of the flow: the scan by safety level
 * ([scanRows]), the batches with their check ([batchRows]), the undo list ([undoRows]), the
 * before/after report ([reportRows]) and the commands for TVs that refuse Couch's shell
 * ([commandRows]; Fire OS does). The scan and every check use the package manager, so they work
 * everywhere; only removing and restoring need the shell.
 */
class DebloatPage(internal val ctx: SettingsContext) : SettingsPage {
    internal enum class Step { SCAN, BATCH, UNDO, REPORT, COMMANDS }

    internal val o = ctx.options
    /** Lazy, so the catalog is first read on the scan's worker rather than as the page opens. */
    internal val list by lazy { DebloatList.forManufacturer(Catalog.of(ctx.activity), Build.MANUFACTURER) }
    internal val engine by lazy {
        engineStarted = true
        Engine(ctx.activity, list)
    }
    private var engineStarted = false
    internal val store = DebloatStore.load(ctx.files.dir)
    internal val icons = AppIcons(ctx.activity)

    internal var step = if (store.flow != null) Step.BATCH else Step.SCAN
    internal var scan: DebloatList.Scan? = null
    internal var impacts: Map<String, Impact> = emptyMap()

    /** The "no gain" apps are folded away until asked for. */
    internal var showIdle = false
    internal val picked = LinkedHashSet<String>()

    /** A worker is talking to the shell; Back waits for it. */
    @Volatile internal var busy = false
    internal var error: String? = null

    /** Set once the TV refused the shell for a restore; the undo list shows commands from then on. */
    internal var restoreByHand = false

    init {
        if (step == Step.SCAN) rescan()
    }

    override fun onDestroy() {
        // The shell stays open across the page's actions, so the TV asks at most once per visit.
        // Untouched, it never opened, and forcing it here would make a key for nothing.
        if (engineStarted) engine.close()
    }

    override fun consumesKey(event: KeyEvent) =
        busy && event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK

    override fun build() {
        Tokens.applyAccent(Tokens.GODMODE_ACCENT)
        ctx.layout.title.text = ctx.str(R.string.debloat_title)
        ctx.layout.hint.text = ""
        error?.let { o.header(it) }
        when (step) {
            Step.SCAN -> scanRows()
            Step.BATCH -> batchRows()
            Step.UNDO -> undoRows()
            Step.REPORT -> reportRows()
            Step.COMMANDS -> commandRows()
        }
    }

    internal fun go(next: Step) {
        step = next
        if (next == Step.SCAN) rescan() else rebuild()
    }

    /** Fast (one package-manager lookup per known package), but kept off the UI thread anyway. */
    internal fun rescan() = work {
        val installed = Packages.installedOf(ctx.activity, list.known)
        val result = list.scan(installed)
        val memory = if (Keepalive.isGranted(ctx.activity)) engine.memory(result.removable.map { it.pkg }) else null
        val measured = Impacts.measure(ctx.activity, result.removable.map { it.pkg }, memory)
        store.saw(ramFreeMb(), storageFreeMb())
        ui {
            scan = result
            impacts = measured
            picked.retainAll(result.removable.map { it.pkg }.toSet())
        }
    }

    /** Runs [block] on a worker; the page rebuilds when it's done, whatever happened. */
    internal fun work(block: () -> Unit) {
        thread {
            try {
                block()
            } catch (e: Exception) {
                ui { error = e.message ?: e.javaClass.simpleName }
            } finally {
                busy = false
                ui { rebuild() }
            }
        }
    }

    /** Worker threads land here; skipped while the activity is leaving. */
    internal fun ui(block: () -> Unit) {
        ctx.activity.runOnUiThread { if (!ctx.activity.isFinishing) block() }
    }

    internal fun toast(text: String) = ui { Toast.makeText(ctx.activity, text, Toast.LENGTH_LONG).show() }

    /** Rebuilds the list, then focuses the first row or tile. */
    internal fun rebuild() = ctx.layout.list.post {
        ctx.layout.list.removeAllViews()
        build()
        // The first tile sits inside a grid, so let focus search down for it.
        ctx.layout.list.requestFocus(View.FOCUS_DOWN)
    }

    internal fun label(pkg: String) = Packages.label(ctx.activity, pkg)

    /** Shown when the shell can't be had; [Engine.State.BLOCKED] is handled by the caller. */
    internal fun shellError(state: Engine.State) = ctx.str(
        if (state == Engine.State.DEBUG_OFF) R.string.debug_off_error else R.string.shell_no_ok,
    )

    internal fun bootCount() = Settings.Global.getInt(ctx.activity.contentResolver, Settings.Global.BOOT_COUNT, -1)

    internal fun ramFreeMb(): Int {
        val mem = ActivityManager.MemoryInfo()
        ctx.activity.getSystemService(ActivityManager::class.java)?.getMemoryInfo(mem)
        return (mem.availMem / MB).toInt()
    }

    internal fun storageFreeMb() = (StatFs(Environment.getDataDirectory().path).availableBytes / MB).toInt()

    companion object {
        const val page = "debloat"
        internal const val BATCH = 4
        private const val MB = 1024L * 1024L
    }
}
