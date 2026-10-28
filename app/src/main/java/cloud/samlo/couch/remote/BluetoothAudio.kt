package cloud.samlo.couch.remote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import cloud.samlo.couch.R

/**
 * The paired Bluetooth audio devices (headphones, speakers) as quick-settings rows, and which one
 * the sound goes to. Android lets only system apps connect one, so OK opens the device's page in
 * the TV's settings, where Connect is the first choice. Needs BLUETOOTH_CONNECT on Android 12 and
 * later, which Couch grants itself over ADB (see [RemoteButtons]); without it, no rows.
 */
@SuppressLint("MissingPermission")
class BluetoothAudio(private val context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    @Volatile private var a2dp: BluetoothA2dp? = null

    /** Asks Android for the A2DP profile once; its answer comes back on its own. */
    fun start() {
        if (!allowed() || a2dp != null) return
        runCatching {
            adapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    a2dp = proxy as? BluetoothA2dp
                }

                override fun onServiceDisconnected(profile: Int) {
                    a2dp = null
                }
            }, BluetoothProfile.A2DP)
        }
    }

    fun release() {
        a2dp?.let { proxy -> runCatching { adapter?.closeProfileProxy(BluetoothProfile.A2DP, proxy) } }
        a2dp = null
    }

    /** The name of the device the sound goes to now, or null for the TV's own outputs. */
    fun connectedName(): String? = if (!allowed()) null else runCatching { a2dp?.connectedDevices?.firstOrNull()?.name }.getOrNull()

    fun items(section: String): List<PanelItem> {
        if (!allowed()) return emptyList()
        val devices = runCatching { adapter?.bondedDevices.orEmpty().filter(::isAudio).sortedBy { it.name.orEmpty() } }.getOrDefault(emptyList())
        return devices.map { device ->
            PanelItem(
                "bt:${device.address}", section, device.name ?: device.address, PanelItem.Icon.Glyph(R.drawable.qs_headphones),
                detail = { context.getString(if (connected(device)) R.string.bt_connected else R.string.bt_not_connected) },
            ) { open(device) }
        }
    }

    private fun connected(device: BluetoothDevice) = runCatching { a2dp?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED }.getOrDefault(false)

    /** Headphones, speakers and sound bars, not the remote or a keyboard. */
    private fun isAudio(device: BluetoothDevice) = device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO

    /** The device's page in the TV's settings, the same page Settings → Remotes and accessories opens. */
    private fun open(device: BluetoothDevice) {
        val uri = Uri.withAppendedPath(Uri.parse(DEVICE_SLICE), "${device.address} ${device.name.orEmpty()}")
        val intent = Intent("android.settings.SLICE_SETTINGS").putExtra("slice_uri", uri.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // A TV without Android TV's settings slices: its Bluetooth settings at least.
            runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private fun allowed() = adapter != null &&
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.checkSelfPermission(CONNECT) == PackageManager.PERMISSION_GRANTED)

    companion object {
        const val CONNECT = "android.permission.BLUETOOTH_CONNECT"
        private const val DEVICE_SLICE = "content://com.android.tv.settings.accessories.sliceprovider/device"
    }
}
