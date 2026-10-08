// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.Intent
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.proto.TestInstance
import kotlinx.coroutines.*

/** Bound UI observer only; destroy/unbind never stops the module. @author 雾晚 */
class RootTunService : Service(), BaseService.Interface {
    override val data = BaseService.Data(this)
    override val tag = "WanBoxModuleObserver"
    override val powerLocks = ServicePowerLocks()
    override var upstreamInterfaceName: String? = null
    private var observer: Job? = null
    @Volatile private var snapshot: RootModuleClient.Status? = null
    override fun createNotification(profileName: String): ServiceNotification = error("Module observer does not own a foreground service")
    override fun acquireWakeLock() = Unit
    override fun currentProfileName() = snapshot?.profileName ?: ""
    override fun onBind(intent: Intent): android.os.IBinder? {
        if (observer?.isActive != true) observer = data.serviceScope.launch {
            val networkOwner = Any()
            RootModuleObservation.run(network = {
                try {
                    io.nekohasekai.sagernet.utils.DefaultNetworkListener.start(networkOwner) { network ->
                        SagerNet.underlyingNetwork = network
                    }
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { io.nekohasekai.sagernet.utils.DefaultNetworkListener.stop(networkOwner) }
                }
            }, sample = {
                try {
                    val status = RootModuleClient.call("status")
                    val changedError = snapshot?.error != status.error
                    snapshot = status; DataStore.currentProfile = if (status.connected) status.profileId else 0
                    DataStore.mixedInboundAuthed = status.connected && DataStore.mixedInboundNeedsAuth
                    data.changeState(status.state, if (changedError && status.error.isNotBlank())
                        getString(io.nekohasekai.sagernet.R.string.root_module_action_failed) + " (" + status.error + ")" else null)
                    status.stats?.let { stats -> data.binder.broadcast {
                        it.cbSpeedUpdate(io.nekohasekai.sagernet.aidl.SpeedDisplayData(stats.tx, stats.rx, stats.directTx, stats.directRx, 0, 0))
                    } }
                } catch (e: CancellationException) { throw e }
                  catch (_: Exception) {
                    snapshot = null; DataStore.currentProfile = 0; DataStore.mixedInboundAuthed = false
                    data.changeState(BaseService.State.Stopped)
                }
            }) // Bound UI/tile only, never an App background watchdog.
        }
        return super<BaseService.Interface>.onBind(intent)
    }
    fun urlTest(url: String, timeoutMs: Int): Int = runBlocking(Dispatchers.IO) {
        val before = RootModuleClient.call("status")
        if (!before.connected) return@runBlocking 0
        val result = if (DataStore.mixedInboundDisabled) {
            val profile = SagerDatabase.proxyDao.getById(before.profileId) ?: return@runBlocking 0
            TestInstance(profile, url, timeoutMs).doTest()
        } else io.nekohasekai.sagernet.utils.ProxyUrlProbe.measure(url, DataStore.mixedPort, timeoutMs,
            DataStore.mixedUsername.takeIf { DataStore.mixedInboundNeedsAuth }, DataStore.mixedPassword)
        val after = RootModuleClient.call("status")
        if (after.connected && after.runningRevision == before.runningRevision) result else 0
    }
    override fun reload() { SagerNet.reloadService() }
    override fun startRunner() { SagerNet.startService() }
    override fun stopRunner(restart: Boolean, msg: String?) { if (restart) SagerNet.reloadService() else SagerNet.stopService() }
    override fun onUnbind(intent: Intent?): Boolean { observer?.cancel(); observer = null; stopSelf(); return super.onUnbind(intent) }
    override fun onDestroy() { observer?.cancel(); data.serviceScope.cancel(); data.binder.close(); super.onDestroy() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
}
