// @author 雾晚
package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import android.app.ActivityManager
import android.widget.Toast
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.BootReceiver
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import io.nekohasekai.sagernet.utils.LandingIpManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libcore.Libcore
import moe.matsuri.nb4a.NativeInterface
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.utils.Util
import java.io.File
import java.net.UnknownHostException

class BaseService {

    enum class State(
        val canStop: Boolean = false,
        val started: Boolean = false,
        val connected: Boolean = false,
    ) {
        /**
         * Idle state is only used by UI and will never be returned by BaseService.
         */
        Idle, Connecting(true, true, false), Connected(true, true, true), Stopping, Stopped,
    }

    interface ExpectedException

    class Data internal constructor(internal val service: Interface) {
        @Volatile var state = State.Stopped
        @Volatile var proxy: ProxyInstance? = null
        var notification: ServiceNotification? = null
        var cacheRecoveryAttempts = 0
        var networkSwitchRetryAttempts = 0

        val receiver = broadcastReceiver { ctx, intent ->
            when (intent.action) {
                Intent.ACTION_SHUTDOWN -> service.persistStats()
                Action.RELOAD -> service.reload()
                Action.REFRESH_NOTIFICATION -> runOnIoDispatcher { notification?.refreshPreferences() }
                Action.RESTART -> {
                    Logs.i("BaseService: received Action.RESTART, forcing full stopRunner(restart = true)")
                    service.stopRunner(restart = true)
                }
                // Action.SWITCH_WAKE_LOCK -> runOnDefaultDispatcher { service.switchWakeLock() }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        // @author 雾晚: Doze keeps the core alive without explicit GC or data-path pausing.
                        if (!SagerNet.power.isDeviceIdleMode) {
                            runCatching { proxy?.box }.getOrNull()?.wake()
                            if (DataStore.wakeResetConnections) {
                                Libcore.resetAllConnections(true)
                            }
                        }
                    }
                }

                Action.RESET_UPSTREAM_CONNECTIONS -> {
                    Logs.i("BaseService: Action.RESET_UPSTREAM_CONNECTIONS received, resetting connections and restarting runner")
                    runOnDefaultDispatcher {
                        try {
                            Libcore.resetAllConnections(true)
                        } catch (e: Throwable) {
                            Logs.w(e)
                        }
                        LandingIpManager.clearCache()
                        service.stopRunner(restart = true)
                        runOnMainDispatcher {
                            Util.collapseStatusBar(ctx)
                            Toast.makeText(
                                ctx,
                                ctx.getString(R.string.reset_connections_done),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }

                Action.SWITCH_PERFORMANCE_MODE -> {
                    val enabled = DataStore.performancePriorityMode
                    Logs.i("BaseService: SWITCH_PERFORMANCE_MODE received, enabled=$enabled")
                }

                // @author 雾晚: handle explicitly so screen-off never falls through to stopRunner().
                Intent.ACTION_SCREEN_OFF -> Unit

                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> {
                    runCatching { proxy?.box }.getOrNull()?.wake()
                    runOnDefaultDispatcher {
                        proxy?.looper?.postLastSnapshotSpeed()
                    }
                    if (DataStore.wakeResetConnections) {
                        Libcore.resetAllConnections(true)
                    }
                }

                Action.CLOSE -> service.stopRunner()

                else -> service.stopRunner()
            }
        }
        var closeReceiverRegistered = false

        val binder = Binder(this)
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var destroyed = false
        var stoppingJob: Job? = null
        var connectingJob: Job? = null

        fun changeState(s: State, msg: String? = null) {
            if (state == s && msg == null) return
            state = s
            DataStore.serviceState = s
            binder.stateChanged(s, msg)
            runCatching { io.nekohasekai.sagernet.widget.OwnBoxWidgetProvider.updateWidgets(SagerNet.application) }
        }
    }

    class Binder(private var data: Data? = null) : ISagerNetService.Stub(), CoroutineScope,
        AutoCloseable {
        private val callbacks = object : RemoteCallbackList<ISagerNetServiceCallback>() {
            override fun onCallbackDied(callback: ISagerNetServiceCallback?, cookie: Any?) {
                super.onCallbackDied(callback, cookie)
            }
        }

        val callbackIdMap = mutableMapOf<ISagerNetServiceCallback, Int>()

        override val coroutineContext = Dispatchers.Main.immediate + Job()

        override fun getState(): Int = (data?.state ?: State.Idle).ordinal
        override fun getProfileName(): String = data?.proxy?.displayProfileName ?: "Idle"

        override fun registerCallback(cb: ISagerNetServiceCallback, id: Int) {
            if (id == SagerConnection.CONNECTION_ID_RESTART_BG) {
                Runtime.getRuntime().exit(0)
                return
            }
            if (!callbackIdMap.contains(cb)) {
                callbacks.register(cb)
            }
            callbackIdMap[cb] = id
        }

        private val broadcastMutex = Mutex()

        suspend fun broadcast(work: (ISagerNetServiceCallback) -> Unit) {
            broadcastMutex.withLock {
                val count = callbacks.beginBroadcast()
                try {
                    repeat(count) {
                        try {
                            work(callbacks.getBroadcastItem(it))
                        } catch (_: RemoteException) {
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    callbacks.finishBroadcast()
                }
            }
        }

        override fun unregisterCallback(cb: ISagerNetServiceCallback) {
            callbackIdMap.remove(cb)
            callbacks.unregister(cb)
        }

        override fun resetTraffic(profileIds: LongArray) {
            launch(Dispatchers.Default) {
                data?.proxy?.looper?.resetTraffic(profileIds)
            }
        }

        override fun urlTest(): Int = measureUrl(
            DataStore.connectionTestURL, DataStore.connectionTestTimeout, false
        )

        override fun urlTestCustomUrl(url: String, timeoutMs: Int): Int =
            measureUrl(url, timeoutMs, true)

        private fun measureUrl(url: String, timeoutMs: Int, custom: Boolean): Int {
            val current = data ?: return 0
            // The owning service is available even when Root TUN has no ProxyInstance/box yet.
            val root = current.service as? RootTunService
            return try {
                ConnectedUrlTest.measure(
                    current.state.connected, url, timeoutMs,
                    root?.let { service -> { target, timeout -> service.urlTest(target, timeout) } },
                    { runCatching { current.proxy?.box }.getOrNull() }
                ) { box, target, timeout ->
                    if (custom) Libcore.urlTestFull(box, target, timeout)
                    else Libcore.urlTest(box, target, timeout)
                }
            } catch (e: Exception) {
                error(Protocols.genFriendlyMsg(e.readableMessage))
            }
        }

        override fun postNotificationSpeed(speed: io.nekohasekai.sagernet.aidl.SpeedDisplayData) {
            launch {
                data?.notification?.postNotificationSpeedUpdate(speed)
            }
        }

        fun stateChanged(s: State, msg: String?) = launch {
            val profileName = profileName
            broadcast { it.stateChanged(s.ordinal, profileName, msg) }
        }

        fun missingPlugin(pluginName: String) = launch {
            val profileName = profileName
            broadcast { it.missingPlugin(profileName, pluginName) }
        }

        override fun close() {
            callbacks.kill()
            cancel()
            data = null
        }
    }

    interface Interface {
        val data: Data
        val tag: String
        fun createNotification(profileName: String): ServiceNotification

        fun onBind(intent: Intent): IBinder? =
            if (intent.action == Action.SERVICE) data.binder else null

        fun reload() {
            if (DataStore.selectedProxy == 0L) {
                stopRunner(false, (this as Context).getString(R.string.profile_empty))
                return
            }
            if (canReloadSelector()) {
                val proxy = data.proxy
                val box = runCatching { proxy?.box }.getOrNull()
                val tag = proxy?.config?.profileTagMap?.get(DataStore.selectedProxy) ?: ""
                if (box != null && tag.isNotBlank()) {
                    try {
                        box.selectOutbound(tag)
                        return
                    } catch (e: Exception) {
                        Logs.w("Failed to selectOutbound($tag): ${e.message}, restarting service")
                    }
                }
            }
            val s = data.state
            when {
                s == State.Stopped -> startRunner()
                s.canStop -> stopRunner(true)
                else -> {
                    Logs.w("State $s encountered during reload, restarting runner")
                    stopRunner(true)
                }
            }
        }

        fun canReloadSelector(): Boolean {
            val proxy = data.proxy ?: return false
            runCatching { proxy.box }.getOrNull() ?: return false
            if (data.state != State.Connected) return false
            if (proxy.config.selectorGroupId < 0L) return false
            val profileId = DataStore.selectedProxy
            if (profileId <= 0L) return false
            val tag = proxy.config.profileTagMap[profileId]
            return !tag.isNullOrBlank()
        }

        suspend fun startProcesses() {
            data.proxy!!.launch()
        }

        fun startRunner() {
            if (data.destroyed) return
            this as Context
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, javaClass))
            else startService(Intent(this, javaClass))
        }

        fun deleteCorruptedCacheDb() {
            val context = this as Context
            val candidates = mutableListOf<File>()

            runCatching {
                candidates.add(File(context.cacheDir, "cache.db"))
                context.cacheDir.listFiles { f -> f.name.startsWith("cache.db") }?.let { candidates.addAll(it) }
            }
            runCatching {
                candidates.add(File(context.filesDir, "cache.db"))
                context.filesDir.listFiles { f -> f.name.startsWith("cache.db") }?.let { candidates.addAll(it) }
            }
            runCatching {
                candidates.add(File(context.noBackupFilesDir, "cache.db"))
                context.noBackupFilesDir.listFiles { f -> f.name.startsWith("cache.db") }?.let { candidates.addAll(it) }
            }
            runCatching {
                context.filesDir.parentFile?.let { parent ->
                    val parentCache = File(parent, "cache")
                    if (parentCache.exists()) {
                        candidates.add(File(parentCache, "cache.db"))
                        parentCache.listFiles { f -> f.name.startsWith("cache.db") }?.let { candidates.addAll(it) }
                    }
                }
            }

            candidates.distinctBy { it.absolutePath }.forEach { file ->
                runCatching {
                    if (file.exists()) {
                        val deleted = file.delete()
                        Logs.i("Auto-recovery: delete cache file ${file.absolutePath}, success=$deleted")
                    }
                }.onFailure {
                    Logs.w("Auto-recovery: failed to delete ${file.absolutePath}", it)
                }
            }
        }

        suspend fun killProcesses(): Throwable? {
            val proxy = data.proxy
            val serviceId = Integer.toHexString(System.identityHashCode(data))
            val proxyId = proxy?.let { Integer.toHexString(System.identityHashCode(it)) } ?: "none"
            var cleanupError: Throwable? = null
            fun recordCleanupFailure(stage: String, error: Throwable) {
                if (cleanupError == null) {
                    cleanupError = error
                } else if (cleanupError !== error) {
                    cleanupError?.addSuppressed(error)
                }
                Logs.w(
                    "ServiceLifecycleTrace serviceId=$serviceId proxyId=$proxyId " +
                        "profileId=${proxy?.profile?.id ?: -1L} stage=$stage failed " +
                        "type=${error.javaClass.name} message=${error.message}"
                )
            }
            Logs.i(
                "ServiceLifecycleTrace serviceId=$serviceId proxyId=$proxyId " +
                    "profileId=${proxy?.profile?.id ?: -1L} stage=kill begin"
            )
            try {
                withContext(Dispatchers.IO) {
                    proxy?.close()
                }
                Logs.i(
                    "ServiceLifecycleTrace serviceId=$serviceId proxyId=$proxyId " +
                        "profileId=${proxy?.profile?.id ?: -1L} stage=proxy-close success"
                )
            } catch (error: Throwable) {
                recordCleanupFailure("proxy-close", error)
            }

            powerLocks.releaseAll()?.let { error -> recordCleanupFailure("wake-lock-release", error) }

            try {
                DefaultNetworkListener.stop(this)
            } catch (error: Throwable) {
                recordCleanupFailure("network-listener-stop", error)
            }

            Logs.i(
                "ServiceLifecycleTrace serviceId=$serviceId proxyId=$proxyId " +
                    "profileId=${proxy?.profile?.id ?: -1L} stage=kill done " +
                    "hasCleanupError=${cleanupError != null}"
            )
            return cleanupError
        }

        // @author 雾晚: called only after the old service has finished cleanup.
        fun onRunnerStopped(restart: Boolean) {}

        fun destroyRunner() {
            data.destroyed = true
            if (data.state != State.Stopped) stopRunner()
            val cleanup = data.stoppingJob
            if (cleanup == null) {
                data.serviceScope.cancel()
                data.binder.close()
            } else cleanup.invokeOnCompletion {
                data.serviceScope.cancel()
                data.binder.close()
            }
        }

        fun stopRunner(restart: Boolean = false, msg: String? = null) {
            // Serialize network callbacks, UI stops and service destruction on Main.
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                data.serviceScope.launch { stopRunner(restart, msg) }
                return
            }
            DataStore.baseService = null
            DataStore.vpnService = null
            DataStore.mixedInboundAuthed = false
            if (!restart) {
                data.cacheRecoveryAttempts = 0
                data.networkSwitchRetryAttempts = 0
            }

            val serviceId = Integer.toHexString(System.identityHashCode(data))
            val proxy = data.proxy
            val proxyId = proxy?.let { Integer.toHexString(System.identityHashCode(it)) } ?: "none"
            val caller = Thread.currentThread().stackTrace.firstOrNull { frame ->
                frame.className != Thread::class.java.name && frame.methodName != "stopRunner"
            }?.let { frame -> "${frame.className}.${frame.methodName}:${frame.lineNumber}" }
                ?: "unknown"
            Logs.i(
                "ServiceStopTrace serviceId=$serviceId proxyId=$proxyId restart=$restart " +
                    "state=${data.state} profileId=${proxy?.profile?.id ?: -1L} " +
                    "hasMessage=${msg != null} caller=$caller"
            )
            if (data.state == State.Stopping) {
                Logs.i(
                    "ServiceStopTrace serviceId=$serviceId proxyId=$proxyId " +
                        "stage=ignored-already-stopping"
                )
                return
            }
            this as Service

            data.changeState(State.Stopping)
            val originalMessage = msg

            data.stoppingJob = data.serviceScope.launch {
                var cleanupError: Throwable? = null
                fun recordCleanupFailure(stage: String, error: Throwable) {
                    if (cleanupError == null) {
                        cleanupError = error
                    } else if (cleanupError !== error) {
                        cleanupError?.addSuppressed(error)
                    }
                    Logs.w(
                        "ServiceStopTrace serviceId=$serviceId proxyId=$proxyId " +
                            "stage=$stage failed type=${error.javaClass.name} " +
                            "message=${error.message}"
                    )
                }

                try {
                    data.connectingJob?.cancelAndJoin() // ensure stop connecting first
                } catch (error: Throwable) {
                    recordCleanupFailure("connecting-job-cancel", error)
                } finally {
                    data.connectingJob = null
                }

                try {
                    data.notification?.destroy()
                } catch (error: Throwable) {
                    recordCleanupFailure("notification-destroy", error)
                } finally {
                    data.notification = null
                }

                try {
                    killProcesses()?.let { recordCleanupFailure("process-cleanup", it) }
                } catch (error: Throwable) {
                    recordCleanupFailure("process-cleanup-boundary", error)
                }

                try {
                    if (data.closeReceiverRegistered) {
                        unregisterReceiver(data.receiver)
                    }
                } catch (error: Throwable) {
                    recordCleanupFailure("receiver-unregister", error)
                } finally {
                    data.closeReceiverRegistered = false
                    data.proxy = null
                }

                cleanupError?.let { error ->
                    Logs.w(
                        "ServiceStopTrace serviceId=$serviceId proxyId=$proxyId " +
                            "stage=cleanup failed type=${error.javaClass.name} " +
                            "message=${error.message} suppressed=${error.suppressed.size} " +
                            "originalMessagePreserved=${originalMessage != null}"
                    )
                }

                try {
                    data.changeState(State.Stopped, originalMessage)
                } catch (error: Throwable) {
                    recordCleanupFailure("state-stopped", error)
                }
                Logs.i(
                    "ServiceStopTrace serviceId=$serviceId proxyId=$proxyId " +
                        "stage=stopped restart=$restart hasCleanupError=${cleanupError != null}"
                )

                try {
                    // stop the service if nothing has bound to it
                    if (restart && !data.destroyed) {
                        delay(100)
                        startRunner()
                    } else {
                        stopSelf()
                    }
                    onRunnerStopped(restart)
                } catch (error: Throwable) {
                    recordCleanupFailure("service-finish", error)
                }
            }
        }

        fun persistStats() {
            // TODO NEW save app stats?
        }

        // networks
        var upstreamInterfaceName: String?

        suspend fun preInit() {
            // 只负责 underlyingNetwork / 网卡名跟踪，供 VpnService.setUnderlyingNetworks。
            // 「网络变化时重置出站」由 DataStore.networkChangeResetConnections 控制，
            // 经 NativeInterface → Libcore.setNetworkChangeResetConnections →
            // interfaceMonitor 是否 callback → 官方 ResetNetwork 生效；
            // 此处不再叠调 resetAllConnections（避免与内核双路径各拆一次）。
            // 「唤醒时重置」见 receiver 内 DataStore.wakeResetConnections。
            DefaultNetworkListener.start(this) { network ->
                if (network == null) {
                    SagerNet.underlyingNetwork = null
                    upstreamInterfaceName = null
                    NativeInterface.clearInterfaceCache()
                    DataStore.vpnService?.updateUnderlyingNetwork()
                    return@start
                }
                SagerNet.connectivity.getLinkProperties(network)?.also { link ->
                    val oldNetwork = SagerNet.underlyingNetwork
                    SagerNet.underlyingNetwork = network
                    DataStore.vpnService?.updateUnderlyingNetwork()
                    val oldName = upstreamInterfaceName
                    if (oldName != link.interfaceName || oldNetwork != network) {
                        Logs.d("Network changed: $oldName -> ${link.interfaceName} (network $oldNetwork -> $network)")
                        upstreamInterfaceName = link.interfaceName
                        NativeInterface.clearInterfaceCache()
                        // @author 雾晚: the first network callback is discovery, not a switch.
                        if (data.state == State.Connecting && oldNetwork != null && oldName != null) {
                            Logs.i("Network changed during Connecting state: restarting after cleanup")
                            stopRunner(restart = true)
                            return@start
                        }
                        if (DataStore.networkChangeResetConnections) {
                            try {
                                Libcore.resetAllConnections(true)
                            } catch (e: Exception) {
                                Logs.w(e)
                            }
                        }
                    }
                }
            }
        }

        val powerLocks: ServicePowerLocks
        fun acquireWakeLock()

        suspend fun lateInit() {
            powerLocks.releaseAll()?.let { throw it }

            if (DataStore.acquireWakeLock) {
                acquireWakeLock()
                data.notification?.postNotificationWakeLockStatus(true)
            } else {
                data.notification?.postNotificationWakeLockStatus(false)
            }
        }

        fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            if (data.destroyed) return Service.START_NOT_STICKY
            DataStore.baseService = this

            val data = data
            if (data.state == State.Connecting && data.proxy == null && data.connectingJob?.isActive != true) {
                data.changeState(State.Stopped)
            }
            if (data.state != State.Stopped) return Service.START_STICKY
            var profile = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
            if (profile == null) {
                profile = SagerDatabase.proxyDao.getAll().firstOrNull()?.also {
                    DataStore.selectedProxy = it.id
                }
            }
            this as Context
            if (profile == null) { // gracefully shutdown: https://stackoverflow.com/q/47337857/2245107
                try { data.notification = createNotification("") }
                catch (e: RuntimeException) { Logs.w("Foreground promotion failed for empty profile", e) }
                stopRunner(false, getString(R.string.profile_empty))
                return Service.START_NOT_STICKY
            }

            val proxy = ProxyInstance(profile, this)
            data.proxy = proxy
            BootReceiver.enabled = DataStore.persistAcrossReboot
            if (!data.closeReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(Action.RELOAD)
                    addAction(Action.RESTART)
                    addAction(Intent.ACTION_SHUTDOWN)
                    addAction(Action.CLOSE)
                    // addAction(Action.SWITCH_WAKE_LOCK)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    }
                    addAction(Action.REFRESH_NOTIFICATION)
                    addAction(Action.RESET_UPSTREAM_CONNECTIONS)
                    addAction(Action.SWITCH_PERFORMANCE_MODE)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(
                        data.receiver,
                        filter,
                        "$packageName.SERVICE",
                        null,
                        Context.RECEIVER_EXPORTED
                    )
                } else {
                    registerReceiver(
                        data.receiver,
                        filter,
                        "$packageName.SERVICE",
                        null
                    )
                }
                data.closeReceiverRegistered = true
            }

            data.changeState(State.Connecting)
            // @author 雾晚: retain the startup job so stop/reload can cancel and join it.
            val connectingJob = data.serviceScope.launch(start = CoroutineStart.LAZY) {
                try {
                    data.notification = createNotification(ServiceNotification.genTitle(profile))

                    Executable.killAll()    // clean up old processes
                    preInit()
                    proxy.init()
                    DataStore.currentProfile = profile.id

                    proxy.processes = GuardedProcessPool {
                        Logs.w(it)
                        stopRunner(false, it.readableMessage)
                    }

                    startProcesses()
                    currentCoroutineContext().ensureActive()
                    if (data.state != State.Connecting) return@launch
                    data.changeState(State.Connected)
                    data.cacheRecoveryAttempts = 0
                    data.networkSwitchRetryAttempts = 0

                    lateInit()
                } catch (_: CancellationException) { // if the job was cancelled, it is canceller's responsibility to call stopRunner
                } catch (_: UnknownHostException) {
                    if (data.networkSwitchRetryAttempts < 3) {
                        data.networkSwitchRetryAttempts++
                        Logs.w("Network switch / transient DNS failure in startRunner: retrying in 600ms (attempt ${data.networkSwitchRetryAttempts}/3)...")
                        delay(600)
                        stopRunner(restart = true)
                        return@launch
                    }
                    stopRunner(false, getString(R.string.invalid_server))
                } catch (e: PluginManager.PluginNotFoundException) {
                    Toast.makeText(this@Interface, e.readableMessage, Toast.LENGTH_SHORT).show()
                    Logs.w(e)
                    data.binder.missingPlugin(e.plugin)
                    stopRunner(false, null)
                } catch (exc: Throwable) {
                    val msg = exc.readableMessage
                    val isCacheCorrupt = msg.contains("invalid freelist page", ignoreCase = true) ||
                            msg.contains("initialize cache-file: timeout", ignoreCase = true) ||
                            msg.contains("initialize cache-file", ignoreCase = true) ||
                            msg.contains("freelist", ignoreCase = true) ||
                            (msg.contains("cache.db", ignoreCase = true) && msg.contains("panic", ignoreCase = true))

                    if (isCacheCorrupt && data.cacheRecoveryAttempts < 1) {
                        data.cacheRecoveryAttempts++
                        Logs.w("Auto-recovery: detected corrupted cache database ($msg). Purging cache.db and retrying once...")
                        deleteCorruptedCacheDb()
                        stopRunner(restart = true)
                        return@launch
                    }
                    data.cacheRecoveryAttempts = 0

                    val isNetworkTransient = msg.contains("network unreachable", ignoreCase = true) ||
                            msg.contains("host unreachable", ignoreCase = true) ||
                            msg.contains("no route to host", ignoreCase = true) ||
                            msg.contains("connection refused", ignoreCase = true) ||
                            msg.contains("i/o timeout", ignoreCase = true) ||
                            msg.contains("timed out", ignoreCase = true) ||
                            msg.contains("connection reset", ignoreCase = true)
                    if (isNetworkTransient && data.networkSwitchRetryAttempts < 3) {
                        data.networkSwitchRetryAttempts++
                        Logs.w("Network transient failure in startRunner ($msg): retrying in 600ms (attempt ${data.networkSwitchRetryAttempts}/3)...")
                        delay(600)
                        stopRunner(restart = true)
                        return@launch
                    }

                    if (exc.javaClass.name.endsWith("proxyerror")) {
                        // error from golang
                        Logs.w(exc.readableMessage)
                    } else {
                        Logs.w(exc)
                    }
                    stopRunner(
                        false, "${getString(R.string.service_failed)}: ${exc.readableMessage}"
                    )
                } finally {
                    if (data.connectingJob === currentCoroutineContext()[Job]) {
                        data.connectingJob = null
                    }
                }
            }
            data.connectingJob = connectingJob
            connectingJob.start()
            return Service.START_STICKY
        }
    }

}
