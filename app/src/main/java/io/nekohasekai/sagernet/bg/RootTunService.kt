// @author 雾晚
package io.nekohasekai.sagernet.bg

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
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
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
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
        if (data.state != BaseService.State.Connected || !readyFile.isFile || rootProcess == null) return 0
        return try {
            if (DataStore.mixedInboundDisabled) {
                val profile = data.proxy?.profile ?: return 0
                return runBlocking { TestInstance(profile, url, timeoutMs).doTest() }
            }
            val target = URL(url)
            val host = target.host
            val port = if (target.port > 0) target.port else target.defaultPort
            if (host.isBlank() || port <= 0) throw IOException("Invalid test URL")
            val timeout = timeoutMs.coerceIn(1_000, 30_000)
            val started = SystemClock.elapsedRealtime()
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", DataStore.mixedPort), timeout)
                socket.soTimeout = timeout
                val authority = if (host.contains(':')) "[$host]:$port" else "$host:$port"
                val credentials = if (DataStore.mixedInboundNeedsAuth) {
                    val value = "${DataStore.mixedUsername}:${DataStore.mixedPassword}"
                    "Proxy-Authorization: Basic ${Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)}\r\n"
                } else ""
                socket.getOutputStream().write(
                    "CONNECT $authority HTTP/1.1\r\nHost: $authority\r\n${credentials}\r\n"
                        .toByteArray(Charsets.US_ASCII)
                )
                val proxyReader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                val proxyStatus = proxyReader.readLine() ?: throw IOException("Proxy closed connection")
                if (!proxyStatus.contains(" 200 ")) throw IOException("Proxy CONNECT failed: $proxyStatus")
                while (true) {
                    if (proxyReader.readLine().isNullOrEmpty()) break
                }
                val stream = if (target.protocol.equals("https", true)) {
                    (SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(socket, host, port, false).also { wrapped ->
                            val tls = wrapped as SSLSocket
                            tls.sslParameters = tls.sslParameters.apply {
                                endpointIdentificationAlgorithm = "HTTPS"
                            }
                            tls.soTimeout = timeout
                            tls.startHandshake()
                        }
                } else socket
                val path = target.file.takeIf { it.isNotBlank() } ?: "/"
                stream.getOutputStream().write(
                    "GET $path HTTP/1.1\r\nHost: $authority\r\nConnection: close\r\n\r\n"
                        .toByteArray(Charsets.UTF_8)
                )
                val status = stream.getInputStream().bufferedReader(Charsets.US_ASCII).readLine()
                    ?: throw IOException("Test server closed connection")
                val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                if (code !in 200..399) throw IOException("Test server returned $code")
                (SystemClock.elapsedRealtime() - started).coerceAtLeast(1).toInt()
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
                    reader.forEachLine { line ->
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

    override fun onBind(intent: Intent) = super<BaseService.Interface>.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        if (DataStore.serviceMode == Key.MODE_ROOT) {
            super<BaseService.Interface>.onStartCommand(intent, flags, startId)
        } else {
            stopSelf()
            START_NOT_STICKY
        }
}
