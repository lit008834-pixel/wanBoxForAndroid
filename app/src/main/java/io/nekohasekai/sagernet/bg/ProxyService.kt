// @author 雾晚
package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.Intent
import io.nekohasekai.sagernet.SagerNet

class ProxyService : Service(), BaseService.Interface {
    override val data = BaseService.Data(this)
    override val tag: String get() = "SagerNetProxyService"
    override fun createNotification(profileName: String): ServiceNotification =
        ServiceNotification(this, profileName, "service-proxy", true)

    override val powerLocks = ServicePowerLocks()
    override var upstreamInterfaceName: String? = null

    override fun acquireWakeLock() {
        powerLocks.acquire("cpu") {
            AndroidPowerLockLease(SagerNet.power, "sagernet:proxy")
        }
    }

    override fun onDestroy() {
        destroyRunner()
        super.onDestroy()
    }

    override fun onBind(intent: Intent) = super.onBind(intent)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        super<BaseService.Interface>.onStartCommand(intent, flags, startId)
}
