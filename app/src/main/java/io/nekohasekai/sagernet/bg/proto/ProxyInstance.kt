package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.runBlocking
import moe.matsuri.nb4a.utils.JavaUtil

class ProxyInstance(profile: ProxyEntity, var service: BaseService.Interface? = null) :
    BoxInstance(profile) {

    var notTmp = true

    var lastSelectorGroupId = -1L
    var displayProfileName = ServiceNotification.genTitle(profile)

    // for TrafficLooper
    var looper: TrafficLooper? = null
    private var closed = false

    override fun buildConfig() {
        super.buildConfig()
        lastSelectorGroupId = super.config.selectorGroupId
        //
        if (notTmp) Logs.d("Core configuration prepared (credentials omitted)")
        if (notTmp && BuildConfig.DEBUG) Logs.d(JavaUtil.gson.toJson(config.trafficMap))
    }

    // only use this in temporary instance
    fun buildConfigTmp() {
        notTmp = false
        buildConfig()
    }

    override suspend fun init() {
        super.init()
        pluginConfigs.forEach { (_, plugin) ->
            val (_, content) = plugin
            Logs.d(content)
        }
    }

    override suspend fun loadConfig() {
        super.loadConfig()
    }

    @Synchronized
    override fun launch() {
        check(!closed && looper == null)
        box.setAsMain()
        super.launch() // start box
        // Register synchronously: close cannot race a deferred looper constructor.
        looper = service?.let { TrafficLooper(it.data, it.data.serviceScope) }
        looper?.start()
    }

    // @author 雾晚: external proxy helpers are still needed by the root core.
    fun launchExternalOnly() {
        launchExternal()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        var closeError: Throwable? = null
        try {
            runBlocking { looper?.stop() }
        } catch (error: Throwable) {
            closeError = error
        } finally {
            looper = null
        }
        try {
            super.close()
        } catch (error: Throwable) {
            if (closeError == null) closeError = error
            else if (closeError !== error) closeError?.addSuppressed(error)
        }
        closeError?.let { throw it }
    }
}
