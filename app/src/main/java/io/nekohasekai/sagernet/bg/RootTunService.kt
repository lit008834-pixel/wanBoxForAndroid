// @author 雾晚
package io.nekohasekai.sagernet.bg

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import kotlin.concurrent.thread

class RootTunService : Service(), BaseService.Interface {
    override val data = BaseService.Data(this)
    override val tag = "SagerNetRootTunService"
    override var wakeLock: PowerManager.WakeLock? = null
    override var upstreamInterfaceName: String? = null
    private var rootProcess: Process? = null
    private var rootWatcher: Job? = null
    private var rootOutputReader: Job? = null
    @Volatile private var lastRootOutput: String? = null
    private var fallbackAfterStop = false
    private val pidFile get() = File(noBackupFilesDir, "root-tun.pid")
    private val readyFile get() = File(noBackupFilesDir, "root-tun.ready")
    private val stopFile get() = File(noBackupFilesDir, "root-tun.stop")

    override fun createNotification(profileName: String) =
        ServiceNotification(this, profileName, "service-proxy", true)

    // @author 雾晚: an HTTP request from the app exercises the Root TUN route itself.
    // The Root core cannot be reached through the in-process Libcore BoxInstance.
    fun urlTest(url: String, timeoutMs: Int): Int {
        if (data.state != BaseService.State.Connected || !readyFile.isFile || rootProcess == null) {
            throw IOException(getString(R.string.root_tun_not_ready))
        }
        val connection = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.connectTimeout = timeoutMs.coerceAtLeast(1)
            connection.readTimeout = timeoutMs.coerceAtLeast(1)
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "wanBox")
            val started = SystemClock.elapsedRealtime()
            val status = connection.responseCode
            if (status >= 500) throw IOException("HTTP $status")
            return (SystemClock.elapsedRealtime() - started).coerceAtLeast(1L).toInt()
        } finally {
            connection.disconnect()
        }
    }

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
        } catch (error: Throwable) {
            if (error !is CancellationException) fallbackAfterStop = true
            throw error
        }
        // @author 雾晚: closing su's pipe during stop may interrupt readLine on Android.
        // An uncaught IOException in this detached coroutine used to crash the :bg process.
        rootOutputReader = runOnIoDispatcher {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        lastRootOutput = line.takeLast(512)
                        Logs.i("Root TUN: $line")
                    }
                }
            } catch (error: IOException) {
                if (data.state != BaseService.State.Stopping && data.state != BaseService.State.Stopped) {
                    Logs.e("Root TUN output stream closed: ${error.message}")
                }
            }
        }
        try {
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
        } catch (error: Throwable) {
            if (error !is CancellationException) fallbackAfterStop = true
            throw error
        }
        rootWatcher = runOnIoDispatcher {
            val exitCode = try {
                process.waitFor()
            } catch (_: InterruptedException) {
                return@runOnIoDispatcher
            }
            if (data.state.canStop) {
                // @author 雾晚: persist the reason even when the user's log level is panic.
                Logs.e("Root TUN unexpectedly exited: $exitCode; ${lastRootOutput ?: "no output"}")
                fallbackAfterStop = true
                stopRunner(false, getString(R.string.root_tun_failed_fallback))
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
