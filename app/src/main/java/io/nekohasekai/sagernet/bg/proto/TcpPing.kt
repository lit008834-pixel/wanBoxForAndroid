// @author 雾晚
package io.nekohasekai.sagernet.bg.proto

import android.net.DnsResolver
import android.net.InetAddresses
import android.os.CancellationSignal
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.InetAddress
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Physical-network node TCP RTT, separately from proxy-channel health. @author 雾晚 */
class TcpPing {
    companion object {
        private val probe = TcpRttProbe()
        fun supports(profile: ProxyEntity): Boolean {
            val bean = profile.requireBean()
            return bean !is io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean &&
                bean !is io.nekohasekai.sagernet.fmt.tuic.TuicBean &&
                bean !is io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean &&
                !bean.serverAddress.isNullOrBlank() && (bean.serverPort ?: 0) in 1..65535
        }
    }
    suspend fun doTest(profile: ProxyEntity): Int {
        val owner = Any()
        io.nekohasekai.sagernet.utils.DefaultNetworkListener.start(owner) { SagerNet.underlyingNetwork = it }
        try { return measureOnPhysicalNetwork(profile) }
        finally { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            io.nekohasekai.sagernet.utils.DefaultNetworkListener.stop(owner)
        } }
    }
    private suspend fun measureOnPhysicalNetwork(profile: ProxyEntity): Int {
        val bean = profile.requireBean()
        if (!supports(profile)) {
            throw UnsupportedOperationException(SagerNet.application.getString(R.string.tcp_rtt_udp_unavailable))
        }
        val host = bean.finalAddress?.takeIf { it.isNotBlank() } ?: bean.serverAddress
        val port = bean.finalPort.takeIf { it != 0 } ?: bean.serverPort ?: 443
        require(!host.isNullOrBlank() && port in 1..65535)
        val network = SagerNet.underlyingNetwork
        if (network == null && DataStore.serviceState.connected) {
            throw IOException(SagerNet.application.getString(R.string.tcp_rtt_network_unavailable))
        }
        return probe.measure(host!!, port, network?.networkHandle ?: 0, resolve = { domain ->
            if (InetAddresses.isNumericAddress(domain)) listOf(InetAddresses.parseNumericAddress(domain))
            else suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                DnsResolver.getInstance().query(network, domain, DnsResolver.FLAG_EMPTY,
                    Dispatchers.IO.asExecutor(), signal, object : DnsResolver.Callback<Collection<InetAddress>> {
                        override fun onAnswer(answer: Collection<InetAddress>, rcode: Int) {
                            if (!continuation.isActive) return
                            if (rcode == 0 && answer.isNotEmpty()) continuation.resume(answer.toList())
                            else continuation.resumeWithException(IOException("DNS query failed ($rcode)"))
                        }
                        override fun onError(error: DnsResolver.DnsException) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    })
            }
        }, bind = { socket ->
            // @author 雾晚: bind to the physical network without Android VpnService.
            network?.bindSocket(socket)
            if (SagerNet.underlyingNetwork != network) throw IOException("Network changed during TCP test")
        })
    }
}
