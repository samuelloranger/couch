package cloud.samlo.couch

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import cloud.samlo.couch.debloat.Keepalive
import cloud.samlo.couch.home.HomeScreen
import cloud.samlo.couch.sync.Sync

/**
 * The launcher entry point. Named in the TV's settings, so it keeps its name and package; the
 * home screen itself lives in [HomeScreen].
 */
class MainActivity : Activity() {
    private lateinit var home: HomeScreen

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        home = HomeScreen(this)
        home.onCreate()
    }

    override fun onResume() {
        super.onResume()
        home.onResume()
        Keepalive.maybe(this)
        Sync.start(this)
    }

    override fun onPause() {
        home.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        home.onDestroy()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_BENCHMARK, false)) home.runBenchmark() else home.onHomePressed()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        home.load()
    }

    // Opted out of predictive back in the manifest, so this legacy hook still swallows Back.
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Home has nowhere to go back to.")
    override fun onBackPressed() = Unit

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = home.consumesKey(event) || super.dispatchKeyEvent(event)

    companion object {
        /** Godmode asks home to run its benchmark: back to the home screen, then the script. */
        const val EXTRA_BENCHMARK = "cloud.samlo.couch.BENCHMARK"
    }
}
