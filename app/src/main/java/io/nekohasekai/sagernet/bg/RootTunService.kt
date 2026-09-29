// @author 雾晚
package io.nekohasekai.sagernet.bg

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.os.PowerManager
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class RootTunService : Service(), BaseService.Interface {
    override val data = BaseService.Data(this)
    override val tag = "SagerNetRootTunService"
    override var wakeLock: PowerManager.WakeLock? = null
    override var upstreamInterfaceName: String? = null
    private var rootProcess: Process? = null
    private var rootWatcher: Job? = null
    private var fallbackAfterStop = false
    private val pidFile get() = File(noBackupFilesDir, "root-tun.pid")
    private val readyFile get() = File(noBackupFilesDir, "root-tun.ready")

    override fun createNotification(profileName: String) =
        ServiceNotification(this, profileName, "service-proxy", true)

    @SuppressLint("WakelockTimeout")
    override fun acquireWakeLock() {
        wakeLock = SagerNet.power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sagernet:root-tun")
            .apply { acquire() }
    }

    override suspend fun startProcesses() {
        if (DataStore.serviceMode != Key.MODE_ROOT) throw IOException("Root TUN mode changed")
        if (!withContext(Dispatchers.IO) { RootAccess.available() }) {
            fallbackAfterStop = true
            throw IOException(getString(R.string.root_unavailable_fallback))
        }
        val proxy = data.proxy ?: throw IOException("Missing proxy configuration")
        proxy.launchExternalOnly()
        val process = withContext(Dispatchers.IO) {
            val executable = File(applicationInfo.nativeLibraryDir, "librootbox.so")
            if (!executable.isFile) throw IOException("Root TUN executable is missing")
            val configFile = File(noBackupFilesDir, "root-tun.json")
            configFile.writeText(proxy.config.config)
            pidFile.delete()
            readyFile.delete()
            fun quote(path: String) = "'${path.replace("'", "'\\''")}'"
            val command = listOf(
                executable.absolutePath,
                configFile.absolutePath,
                SagerNet.application.externalAssets.absolutePath,
                pidFile.absolutePath,
                readyFile.absolutePath,
                android.os.Process.myPid().toString()
            ).joinToString(" ") { quote(it) }
            ProcessBuilder("su", "-c", "exec $command")
                .directory(noBackupFilesDir)
                .redirectErrorStream(true)
                .start()
        }
        rootProcess = process
        runOnIoDispatcher {
            process.inputStream.bufferedReader().forEachLine { Logs.i("Root TUN: $it") }
        }
        try {
            var attempts = 0
            while (!readyFile.isFile && attempts++ < 600) {
                val exited = runCatching { process.exitValue(); true }.getOrDefault(false)
                if (exited) throw IOException("Root TUN exited before becoming ready")
                delay(100)
            }
            if (!readyFile.isFile) throw IOException("Root TUN startup timed out")
        } catch (error: Throwable) {
            fallbackAfterStop = true
            throw error
        }
        rootWatcher = runOnIoDispatcher {
            val exitCode = process.waitFor()
            if (data.state.canStop) {
                Logs.w("Root TUN unexpectedly exited: $exitCode")
                fallbackAfterStop = true
                stopRunner(false, getString(R.string.root_tun_failed_fallback))
            }
        }
    }

    override suspend fun killProcesses(): Throwable? {
        rootWatcher?.cancel()
        rootWatcher = null
        withContext(Dispatchers.IO) {
            val pid = runCatching { pidFile.readText().trim().toInt() }.getOrNull()
            if (pid != null && pid > 1) {
                runCatching { ProcessBuilder("su", "-c", "kill -TERM $pid").start().waitFor() }
            }
            rootProcess?.destroy()
            rootProcess = null
            readyFile.delete()
            pidFile.delete()
        }
        val error = super.killProcesses()
        if (fallbackAfterStop) {
            fallbackAfterStop = false
            runOnDefaultDispatcher {
                delay(500)
                RootAccess.fallbackToVpn(this@RootTunService, R.string.root_tun_failed_fallback)
            }
        }
        return error
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
