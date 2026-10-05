// @author 雾晚
package io.nekohasekai.sagernet.bg

import android.annotation.SuppressLint
import android.net.wifi.WifiManager
import android.os.PowerManager

/** Holds the platform handle until service-owned cleanup succeeds. @author 雾晚 */
class AndroidPowerLockLease(manager: PowerManager, tag: String) : ServicePowerLocks.Lease {
    private val wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply {
        setReferenceCounted(false)
    }
    // Existing explicit setting keeps the lock for the active service, released by its owner.
    @SuppressLint("WakelockTimeout")
    override fun acquire() { wakeLock.acquire() }
    override fun release() { if (wakeLock.isHeld) wakeLock.release() }
}

/** Optional VPN WiFi handle; CPU ownership is independent. @author 雾晚 */
@Suppress("DEPRECATION")
class AndroidWifiLockLease(manager: WifiManager) : ServicePowerLocks.Lease {
    private val wifiLock = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "sagernet:vpn_wifi").apply {
        setReferenceCounted(false)
    }
    override fun acquire() { wifiLock.acquire() }
    override fun release() { if (wifiLock.isHeld) wifiLock.release() }
}
