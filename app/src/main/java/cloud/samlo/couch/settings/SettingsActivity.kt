package cloud.samlo.couch.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import cloud.samlo.couch.data.Config
import cloud.samlo.couch.data.UserFiles
import cloud.samlo.couch.debloat.DebloatPage
import cloud.samlo.couch.godmode.GodmodePage
import cloud.samlo.couch.ui.Tokens

/**
 * Hosts one settings page (main, rows, apps, inputs, permissions…, chosen by an extra) in the shared
 * layout. Each change is saved at once; the home screen picks it up when it comes back.
 */
class SettingsActivity : Activity() {
    private lateinit var page: SettingsPage
    private lateinit var layout: SettingsLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val files = UserFiles(this)
        val config = Config.load(files.dir)
        Tokens.applyAccent(config.accent)
        val name = intent.getStringExtra(EXTRA_PAGE)
        // The main page and the pages inside its groups show the groups down the left.
        val group = Group.of(name)
        layout = SettingsLayout(this, withRail = name == null || group != null)
        val ctx = SettingsContext(this, files, layout, config)
        page = when (name) {
            PAGE_ROWS -> RowsPage(ctx)
            PAGE_INPUTS -> InputsPage(ctx)
            PAGE_ACCESS -> AccessPage(ctx)
            PAGE_APPS -> AppsPage(ctx)
            GodmodePage.page -> GodmodePage(ctx)
            DebloatPage.page -> DebloatPage(ctx)
            PAGE_TVS -> OtherTvsPage(ctx)
            RemotePage.PAGE -> RemotePage(ctx)
            else -> MainPage(ctx)
        }
        setContentView(layout.root)
        page.build()
        group?.let { Rail(ctx, focusable = false).build(it) }
        layout.list.post { if (!page.focusStart()) layout.items().firstOrNull { it.isFocusable }?.requestFocus() }
    }

    /** The godmode pages paint phosphor green; put the user's accent back once they leave. */
    override fun onDestroy() {
        if (this::page.isInitialized) {
            page.onDestroy()
            if (page is GodmodePage || page is DebloatPage) {
                Tokens.applyAccent(Config.load(UserFiles(this).dir).accent)
            }
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::page.isInitialized) page.onResume()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        Setup.onPermissionResult(this, requestCode, grantResults)
        if (::page.isInitialized) page.onResume()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = page.consumesKey(event) || super.dispatchKeyEvent(event)

    companion object {
        private const val EXTRA_PAGE = "page"
        const val PAGE_ROWS = "rows"
        const val PAGE_INPUTS = "inputs"
        const val PAGE_TVS = "tvs"
        const val PAGE_ACCESS = "access"
        const val PAGE_APPS = "apps"
        const val RESULT_ORGANIZE = RESULT_FIRST_USER

        fun intent(context: Context, page: String) =
            Intent(context, SettingsActivity::class.java).putExtra(EXTRA_PAGE, page)
    }
}
