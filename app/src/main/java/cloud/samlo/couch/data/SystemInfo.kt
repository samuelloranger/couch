package cloud.samlo.couch.data

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import java.net.Inet4Address

/** One reading of the TV's state, for the Index footer. Rates need two readings, see [SystemInfo]. */
class SystemReading(
    val address: String?,
    /** NetworkCapabilities.TRANSPORT_*, or -1 offline. */
    val transport: Int,
    /** Wi-Fi signal in dBm, when known. */
    val signalDbm: Int?,
    val rxBytesPerSec: Long?,
    val txBytesPerSec: Long?,
    val memoryUsedPercent: Int,
    val storageUsedPercent: Int,
    val storageFreeBytes: Long,
    val uptimeMs: Long,
    val adb: Boolean,
)

/** Reads [SystemReading]s; keeps the previous byte counters so each read can report a rate. */
class SystemInfo(private val context: Context) {
    private var lastRx = -1L
    private var lastTx = -1L
    private var lastAt = 0L

    /** Forgets the last counters, so the next rate isn't an average over time spent away. */
    fun resetRates() {
        lastAt = 0L
    }

    fun read(): SystemReading {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity?.activeNetwork
        val caps = network?.let { connectivity.getNetworkCapabilities(it) }
        val address = network?.let { connectivity.getLinkProperties(it) }
            ?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
        val transport = when {
            caps == null -> -1
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkCapabilities.TRANSPORT_ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkCapabilities.TRANSPORT_WIFI
            else -> NetworkCapabilities.TRANSPORT_CELLULAR
        }
        // Android 10 and later report Wi-Fi signal here; Android 9 shows no figure.
        val signal = if (Build.VERSION.SDK_INT >= 29) {
            caps?.signalStrength?.takeIf { transport == NetworkCapabilities.TRANSPORT_WIFI && it != Int.MIN_VALUE }
        } else {
            null
        }

        val now = SystemClock.elapsedRealtime()
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val seconds = (now - lastAt) / 1000f
        val known = lastAt > 0 && seconds > 0.5f && rx >= lastRx && tx >= lastTx && rx != TrafficStats.UNSUPPORTED.toLong()
        val rxRate = if (known) ((rx - lastRx) / seconds).toLong() else null
        val txRate = if (known) ((tx - lastTx) / seconds).toLong() else null
        lastRx = rx
        lastTx = tx
        lastAt = now

        val memory = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(it) }
        val memoryUsed = if (memory.totalMem > 0) (100 - memory.availMem * 100 / memory.totalMem).toInt() else 0
        val stat = StatFs(Environment.getDataDirectory().path)
        val storageUsed = if (stat.totalBytes > 0) (100 - stat.availableBytes * 100 / stat.totalBytes).toInt() else 0
        val adb = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1

        return SystemReading(address, transport, signal, rxRate, txRate, memoryUsed, storageUsed, stat.availableBytes, now, adb)
    }
}
