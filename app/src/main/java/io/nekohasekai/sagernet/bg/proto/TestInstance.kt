// @author 雾晚
package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.tryResume
import io.nekohasekai.sagernet.ktx.tryResumeWithException
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.suspendCoroutine

class TestInstance(profile: ProxyEntity, val link: String, private val timeout: Int) :
    BoxInstance(profile) {

    private val traceId = traceSequence.incrementAndGet()
    private val traceName = profile.displayName()

    private fun trace(stage: String, message: String) {
        Logs.d("URLTestTrace ktId=$traceId profileId=${profile.id} profile=$traceName stage=$stage $message")
    }

    suspend fun doTest(): Int = kotlinx.coroutines.coroutineScope {
        val owner = this
        processes = GuardedProcessPool { error ->
            owner.cancel("测速插件退出", error)
        }
        val probe = Libcore.newURLTestSession()
        val cancellation = launch(kotlinx.coroutines.Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try { kotlinx.coroutines.awaitCancellation() } finally { probe.cancel() }
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                init()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                launch()
                if (processes.processCount > 0) delay(500)
                val latency = probe.test(box, link, timeout.coerceIn(1, 30000))
                // Never publish a result from an already cancelled caller.
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                latency
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    probe.cancel()
                    cancellation.cancel()
                    cancellation.join()
                    close()
                }
            }
        }
    }

    protected override fun buildConfig() {
        val started = SystemClock.elapsedRealtime()
        config = buildConfig(profile, true)
        trace(
            "build-config",
            "ok elapsed=${SystemClock.elapsedRealtime() - started}ms " +
                    "externalChains=${config.externalIndex.size}"
        )
    }

    override suspend fun loadConfig() {
        // don't call destroyAllJsi here
        if (BuildConfig.DEBUG) Logs.d("Core configuration prepared (credentials omitted)")
        // 测速实例用 NewTestSingBoxInstance：不注册 PlatformLogWriter，
        // 官方内核不再强制创建 CacheFile/ClashServer（见 libcore/box.go 批注）。
        val started = SystemClock.elapsedRealtime()
        box = Libcore.newTestSingBoxInstance(config.config, LocalResolverImpl)
        trace("create-box", "ok elapsed=${SystemClock.elapsedRealtime() - started}ms")
    }

    private companion object {
        private val traceSequence = AtomicLong()
    }

}
