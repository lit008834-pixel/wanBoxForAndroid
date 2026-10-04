// @author 雾晚
package io.nekohasekai.sagernet.bg

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.os.PowerManager
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.proto.TestInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

class RootTunService : Service(), BaseService.Interface {
    override val data = BaseService.Data(this)
    override val tag = "SagerNetRootTunService"
    override var wakeLock: PowerManager.WakeLock? = null
    override var upstreamInterfaceName: String? = null
    @Volatile private var rootProcess: Process? = null
    private var rootWatcher: Job? = null
    private var rootOutputReader: Job? = null
    @Volatile private var lastRootOutput: String? = null
    private var fallbackAfterStop = false
    private val pidFile get() = File(noBackupFilesDir, "root-tun.pid")
    private val readyFile get() = File(noBackupFilesDir, "root-tun.ready")
    private val stopFile get() = File(noBackupFilesDir, "root-tun.stop")

    override fun createNotification(profileName: String) =
        ServiceNotification(this, profileName, "service-proxy", true)

    @SuppressLint("WakelockTimeout")
    override fun acquireWakeLock() {
        wakeLock = SagerNet.power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sagernet:root-tun")
            .apply { acquire() }
    }

    // @author 雾晚: probe the running root core through its local mixed inbound,
    // avoiding Android FakeIP resolution and the app process's unopened box.
    fun urlTest(url: String, timeoutMs: Int): Int {
        val process = rootProcess
        val proxy = data.proxy
        fun ready() = proxy != null && data.state.connected && readyFile.isFile &&
            rootProcess === process && data.proxy === proxy &&
            ConnectedUrlTest.processAlive(process)
        return try {
            ConnectedUrlTest.guardedRoot(::ready) {
                if (DataStore.mixedInboundDisabled) {
                    val profile = proxy?.profile ?: return@guardedRoot 0
                    runBlocking { TestInstance(profile, url, timeoutMs).doTest() }
                } else {
                    io.nekohasekai.sagernet.utils.ProxyUrlProbe.measure(
                        url, DataStore.mixedPort, timeoutMs,
                        DataStore.mixedUsername.takeIf { DataStore.mixedInboundNeedsAuth },
                        DataStore.mixedPassword
                    )
                }
            }
        } catch (error: Exception) {
            Logs.w("Root TUN URL test failed: ${error.message}")
            0
        }
    }

    override suspend fun startProcesses() {
        if (DataStore.serviceMode != Key.MODE_ROOT) throw IOException("Root TUN mode changed")
        if (!withContext(Dispatchers.IO) { RootAccess.available() }) {
            fallbackAfterStop = true
            throw IOException(getString(R.string.root_unavailable_fallback))
        }
        val proxy = data.proxy ?: throw IOException("Missing proxy configuration")
        proxy.launchExternalOnly()
        val process = try {
            withContext(NonCancellable + Dispatchers.IO) {
                val executable = File(applicationInfo.nativeLibraryDir, "librootbox.so")
                if (!executable.isFile) throw IOException("Root TUN executable is missing")
                val configFile = File(noBackupFilesDir, "root-tun.json")
                configFile.writeText(proxy.config.config)
                lastRootOutput = null
                pidFile.delete()
                readyFile.delete()
                stopFile.delete()
                fun quote(path: String) = "'${path.replace("'", "'\\''")}'"
                val command = listOf(
                    executable.absolutePath,
                    configFile.absolutePath,
                    SagerNet.application.externalAssets.absolutePath,
                    pidFile.absolutePath,
                    readyFile.absolutePath,
                    stopFile.absolutePath,
                    android.os.Process.myPid().toString()
                ).joinToString(" ") { quote(it) }
                ProcessBuilder("su", "-c", "exec $command")
                    .directory(noBackupFilesDir)
                    .redirectErrorStream(true)
                    .start()
                    .also { rootProcess = it }
            }
        } catch (error: IOException) {
            // @author 雾晚: local Root runtime failures can safely fall back after cleanup.
            fallbackAfterStop = true
            throw error
        }
        // @author 雾晚: closing su's pipe during stop may interrupt readLine on Android.
        // An uncaught IOException in this detached coroutine used to crash the :bg process.
        rootOutputReader = runOnIoDispatcher {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        if (line.startsWith(RootNotificationSample.PREFIX)) {
                            val sample = RootNotificationSample.parse(line)
                            if (sample != null && rootProcess === process && data.proxy === proxy &&
                                data.state.connected && readyFile.isFile) {
                                data.notification?.apply {
                                    postActiveOutbound(sample.tag)
                                    if (listenPostSpeed && SagerNet.power.isInteractive) {
                                        postNotificationSpeedUpdate(io.nekohasekai.sagernet.aidl.SpeedDisplayData(
                                            sample.tx, sample.rx, sample.directTx, sample.directRx, 0, 0))
                                    }
                                }
                            }
                            continue
                        }
                        if (rootProcess === process) {
                            lastRootOutput = line.takeLast(512)
                            Logs.i("Root TUN: $line")
                        }
                    }
                }
            } catch (error: IOException) {
                if (rootProcess === process && data.state != BaseService.State.Stopping &&
                    data.state != BaseService.State.Stopped) {
                    Logs.e("Root TUN output stream closed: ${error.message}")
                }
            }
        }
        var attempts = 0
        while (!readyFile.isFile && attempts++ < 600) {
            val exited = runCatching { process.exitValue(); true }.getOrDefault(false)
            if (exited) {
                withTimeoutOrNull(500) { rootOutputReader?.join() }
                throw IOException("Root TUN exited before becoming ready: ${lastRootOutput ?: "no output"}")
            }
            delay(100)
        }
        if (!readyFile.isFile) throw IOException("Root TUN startup timed out")
        rootWatcher = runOnIoDispatcher {
            val exitCode = try {
                process.waitFor()
            } catch (_: InterruptedException) {
                return@runOnIoDispatcher
            }
            if (isActive && rootProcess === process && data.state.canStop) {
                // @author 雾晚: persist the reason even when the user's log level is panic.
                Logs.e("Root TUN unexpectedly exited: $exitCode; ${lastRootOutput ?: "no output"}")
                stopRunner(false, "Root TUN exited: $exitCode; ${lastRootOutput ?: "no output"}")
            }
        }
    }

    override suspend fun killProcesses(): Throwable? {
        rootWatcher?.cancel()
        rootWatcher = null
        rootOutputReader?.cancel()
        rootOutputReader = null
        withContext(Dispatchers.IO) {
            // The root process also watches this file, so cleanup works when su refuses kill.
            stopFile.writeText("stop")
            val pid = runCatching { pidFile.readText().trim().toInt() }.getOrNull()
            if (pid != null && pid > 1) {
                runCatching {
                    val killer = ProcessBuilder("su", "-c", "kill -TERM $pid").start()
                    val waiter = thread(isDaemon = true) { killer.waitFor() }
                    waiter.join(3_000)
                    if (waiter.isAlive) killer.destroy()
                }
            }
            // @author 雾晚: wait for old TUN/routing teardown before starting another node.
            rootProcess?.let { running ->
                val waiter = thread(isDaemon = true) { runCatching { running.waitFor() } }
                waiter.join(3_000)
                if (waiter.isAlive) {
                    running.destroy()
                    waiter.join(1_000)
                }
                if (waiter.isAlive) Logs.w("Root TUN process did not exit after stop request")
                rootProcess = null
            }
            readyFile.delete()
            pidFile.delete()
        }
        return super.killProcesses()
    }

    override fun onRunnerStopped(restart: Boolean) {
        // @author 雾晚: only a missing Root grant changes mode, after cleanup completes.
        val fallback = fallbackAfterStop && !restart && DataStore.serviceMode == Key.MODE_ROOT
        fallbackAfterStop = false
        if (fallback) RootAccess.fallbackToVpn(this)
    }

    override fun onDestroy() {
        destroyRunner()
        super.onDestroy()
    }

    override fun onBind(intent: Intent) = super<BaseService.Interface>.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        if (DataStore.serviceMode == Key.MODE_ROOT) {
            super<BaseService.Interface>.onStartCommand(intent, flags, startId)
        } else {
            stopSelf()
            START_NOT_STICKY
        }
}
