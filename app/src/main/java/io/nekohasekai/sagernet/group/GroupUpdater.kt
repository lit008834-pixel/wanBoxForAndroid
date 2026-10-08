// @author 雾晚
package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.isTLS
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

abstract class GroupUpdater {

    abstract suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    )

    data class Progress(
        var max: Int
    ) {
        var progress by AtomicInteger()
    }

    protected suspend fun forceResolve(
        profiles: List<AbstractBean>, groupId: Long?
    ) {
        val ipv6Mode = DataStore.ipv6Mode
        val candidates = profiles.filter { it !is NaiveBean && !it.serverAddress.isIpAddress() }
        val progress = Progress(candidates.size)
        if (groupId != null) {
            GroupUpdater.progress[groupId] = progress
            GroupManager.postReload(groupId)
        }
        val ipv6First = ipv6Mode >= IPv6Mode.PREFER
        val owner = Any()
        io.nekohasekai.sagernet.utils.DefaultNetworkListener.start(owner) { SagerNet.underlyingNetwork = it }
        try {
        SubscriptionResolutionRunner.run(candidates, resolve = { profile ->
            val underlyingNetwork = SagerNet.underlyingNetwork
            val results = if (
                underlyingNetwork != null &&
                DataStore.enableFakeDns &&
                DataStore.serviceMode == Key.MODE_ROOT
            ) {
                // Root may run while App serviceState is stopped; resolve nodes on the physical network.
                underlyingNetwork.getAllByName(profile.serverAddress).filterNotNull()
            } else {
                InetAddress.getAllByName(profile.serverAddress).filterNotNull()
            }
            if (results.isEmpty()) error("empty response")
            results
        }, onResolved = { profile, results ->
            rewriteAddress(profile, results, ipv6First)
        }, onFailure = { error ->
            // Report only the category, not a node address or resolver message containing it.
            Logs.d("Subscription DNS lookup failed: ${error.javaClass.simpleName}")
        }, onFinished = {
            if (groupId != null) {
                progress.progress++
                GroupManager.postReload(groupId)
            }
        })
        } finally { withContext(NonCancellable) { io.nekohasekai.sagernet.utils.DefaultNetworkListener.stop(owner) } }
    }

    protected fun rewriteAddress(
        bean: AbstractBean, addresses: List<InetAddress>, ipv6First: Boolean
    ) {
        val address = addresses.sortedBy { if (ipv6First) (it !is Inet6Address) else (it !is Inet4Address) }[0].hostAddress

        with(bean) {
            when (this) {
                is HttpBean -> {
                    if (isTLS() && sni.isBlank()) sni = bean.serverAddress
                }
                is StandardV2RayBean -> {
                    when (security) {
                        "tls" -> if (sni.isBlank()) sni = bean.serverAddress
                    }
                }
                is TrojanBean -> {
                    if (sni.isBlank()) sni = bean.serverAddress
                }
                is TrojanGoBean -> {
                    if (sni.isBlank()) sni = bean.serverAddress
                }
                is HysteriaBean -> {
                    if (sni.isBlank()) sni = bean.serverAddress
                }
            }

            bean.serverAddress = address
        }
    }

    companion object {

        val updating = Collections.synchronizedSet<Long>(mutableSetOf())
        val progress = Collections.synchronizedMap<Long, Progress>(mutableMapOf())

        fun startUpdate(proxyGroup: ProxyGroup, byUser: Boolean) {
            runOnDefaultDispatcher {
                executeUpdate(proxyGroup, byUser)
            }
        }

        suspend fun executeUpdate(proxyGroup: ProxyGroup, byUser: Boolean): Boolean {
            return supervisorScope {
                // @author 雾晚: do not schedule writes during a user-confirmed data reset.
                if (io.nekohasekai.sagernet.bg.RootModuleDataUpdate.pending()) return@supervisorScope false
                if (!updating.add(proxyGroup.id)) return@supervisorScope false
                GroupManager.postReload(proxyGroup.id)

                val subscription = proxyGroup.subscription
                if (subscription == null) {
                    finishUpdate(proxyGroup)
                    return@supervisorScope false
                }
                val connected = DataStore.serviceState.connected
                val userInterface = GroupManager.userInterface

                if (byUser && (subscription.link?.startsWith("http://") == true || subscription.updateWhenConnectedOnly) && !connected) {
                    if (userInterface == null || !userInterface.confirm(app.getString(R.string.update_subscription_warning))) {
                        finishUpdate(proxyGroup)
                        return@supervisorScope false
                    }
                }

                try {
                    RawUpdater.doUpdate(proxyGroup, subscription, userInterface, byUser)
                    true
                } catch (e: Throwable) {
                    Logs.w(e)
                    if (byUser) {
                        userInterface?.onUpdateFailure(proxyGroup, e.readableMessage)
                    }
                    finishUpdate(proxyGroup)
                    false
                }
            }
        }


        suspend fun finishUpdate(proxyGroup: ProxyGroup) {
            updating.remove(proxyGroup.id)
            progress.remove(proxyGroup.id)
            GroupManager.postUpdate(proxyGroup)
        }

    }

}
