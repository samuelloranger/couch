package cloud.samlo.couch.godmode

import android.os.Build
import cloud.samlo.couch.R
import cloud.samlo.couch.data.SystemInfo
import cloud.samlo.couch.debloat.Engine

/**
 * For TVs where Couch can't open the debugging shell itself (Fire OS refuses apps, or debugging
 * is off): the one command that grants Couch what the shell would have, typed on a computer, with
 * this TV's address filled in. It checks every two seconds and goes away once the grant lands.
 */
internal fun GodmodePage.adbCard(state: Engine.State) {
    val c = ctx.activity
    if (TvSettings.canWrite(c)) return
    val fireOs = Build.MANUFACTURER.lowercase().startsWith("amazon")
    if (!fireOs && state != Engine.State.BLOCKED && state != Engine.State.DEBUG_OFF) return
    val address = SystemInfo(c).read().address ?: ctx.str(R.string.tv_address)
    val connect = "adb connect $address:5555"
    val grant = "adb shell pm grant ${c.packageName} android.permission.WRITE_SECURE_SETTINGS"
    o.header(ctx.str(R.string.adbcard_group))
    o.link(ctx.str(R.string.adbcard_steps), ctx.str(R.string.adbcard_steps_help)) {}
    o.link(ctx.str(R.string.adbcard_connect), connect, { connect }) {}
    o.link(ctx.str(R.string.adbcard_grant), c.getString(R.string.adbcard_grant_help, grant), { ctx.str(R.string.adbcard_grant_value) }) {}
    val list = ctx.layout.list
    val generation = ++pollGeneration
    val poll = object : Runnable {
        override fun run() {
            // A rebuild starts its own check; this one stops.
            if (!list.isAttachedToWindow || generation != pollGeneration) return
            if (TvSettings.canWrite(c)) {
                toast(ctx.str(R.string.adbcard_done))
                rebuild()
            } else {
                list.postDelayed(this, POLL_MS)
            }
        }
    }
    list.postDelayed(poll, POLL_MS)
}

private const val POLL_MS = 2000L
