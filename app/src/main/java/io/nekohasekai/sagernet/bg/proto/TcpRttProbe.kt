// @author 雾晚
package io.nekohasekai.sagernet.bg.proto

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Measures only a fresh TCP connect, never DNS, TLS or a local proxy handshake. @author 雾晚 */
@OptIn(ExperimentalCoroutinesApi::class)
class TcpRttProbe(
    private val clock: () -> Long = System::nanoTime,
    private val socketFactory: () -> Socket = ::Socket,
) {
    companion object {
        private val io = Dispatchers.IO.limitedParallelism(4)
        private val permits = Semaphore(4)
        private const val TTL = 60_000_000_000L
        private const val CAPACITY = 128
    }
    private data class Key(val network: Long, val host: String)
    private data class Entry(val addresses: List<InetAddress>, val expires: Long)
    private val dns = LinkedHashMap<Key, Entry>(16, .75f, true)
    private val history = LinkedHashMap<Pair<Key, Int>, Int>(16, .75f, true)

    internal fun timeout(previous: Int?) = (previous?.toLong()?.times(4)?.plus(1000) ?: 3000L)
        .coerceIn(3000L, 8000L).toInt()

    suspend fun measure(host: String, port: Int, network: Long,
                        resolve: suspend (String) -> List<InetAddress>,
                        bind: (Socket) -> Unit = {}): Int = permits.withPermit {
        require(host.isNotBlank() && port in 1..65535)
        // A total budget also bounds DNS and fallback addresses; only successful connect is timed.
        withTimeoutOrNull(8000L) {
            val key = Key(network, host)
            val addresses = synchronized(dns) { dns[key]?.takeIf { it.expires > clock() }?.addresses }
                ?: resolve(host).distinct().also {
                    if (it.isEmpty()) throw IOException("DNS returned no address")
                    synchronized(dns) {
                        dns[key] = Entry(it, clock() + TTL)
                        if (dns.size > CAPACITY) dns.remove(dns.keys.first())
                    }
                }
            val endpoint = key to port
            val deadline = timeout(synchronized(history) { history[endpoint] })
            var failure: IOException? = null
            for (address in addresses) {
                ensureActive()
                try {
                    val rtt = connect(address, port, deadline, bind)
                    synchronized(history) {
                        history[endpoint] = rtt
                        if (history.size > CAPACITY) history.remove(history.keys.first())
                    }
                    return@withTimeoutOrNull rtt
                } catch (e: IOException) { failure = e }
            }
            throw failure ?: IOException("TCP connection failed")
        } ?: throw SocketTimeoutException("TCP probe exceeded 8000ms total budget")
    }

    private suspend fun connect(address: InetAddress, port: Int, timeout: Int,
                                bind: (Socket) -> Unit): Int = coroutineScope {
        val socket = socketFactory()
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { runCatching { socket.close() } }
            launch(io) {
                try {
                    if (!continuation.isActive) return@launch
                    socket.tcpNoDelay = true
                    bind(socket)
                    val start = clock()
                    socket.connect(InetSocketAddress(address, port), timeout)
                    val rtt = TimeUnit.NANOSECONDS.toMillis(clock() - start).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                    if (continuation.isActive) continuation.resume(rtt)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                } finally { runCatching { socket.close() } }
            }
        }
    }
}
