// @author 雾晚
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.proto.TcpRttProbe
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** Fake DNS and sockets prove timing, network invalidation and cancellation. @author 雾晚 */
class TcpRttProbeTest {
    private val ip = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    @Test fun dnsExcludedButEachProbeCreatesAndClosesAFreshSocket() = runBlocking {
        var time = 0L; var lookups = 0; var opened = 0; var closed = 0
        val probe = TcpRttProbe(clock = { time }, socketFactory = {
            opened++
            object : Socket() {
                override fun setTcpNoDelay(on: Boolean) { assertTrue(on) }
                override fun connect(endpoint: SocketAddress, timeout: Int) { time += 37_000_000; assertEquals(3000, timeout) }
                override fun close() { closed++ }
            }
        })
        suspend fun run(network: Long) = probe.measure("fixture.invalid", 443, network, {
            lookups++; time += 500_000_000; listOf(ip)
        })
        assertEquals(37, run(1)); assertEquals(37, run(1)); assertEquals(1, lookups)
        assertEquals(37, run(2)); assertEquals(2, lookups)
        time += 61_000_000_000
        assertEquals(37, run(2)); assertEquals(3, lookups)
        assertEquals(4, opened); assertEquals(opened, closed)
        assertEquals(3000, probe.timeout(null)); assertEquals(3000, probe.timeout(30))
        assertEquals(7000, probe.timeout(1500)); assertEquals(8000, probe.timeout(Int.MAX_VALUE))
    }
    @Test fun realLoopbackConnectSucceedsAndFailureIsNotCachedSuccess() = runBlocking {
        val server = ServerSocket(0)
        val probe = TcpRttProbe()
        val port = server.localPort
        try { assertTrue(probe.measure("fixture", port, 1, { listOf(ip) }) > 0) }
        finally { server.close() }
        try { probe.measure("fixture", port, 1, { listOf(ip) }); fail("stale result reused") }
        catch (_: IOException) { }
    }
    @Test fun cancelledConnectClosesSocketAndReleasesPermit() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val closed = CountDownLatch(1)
        val probe = TcpRttProbe(socketFactory = {
            object : Socket() {
                override fun setTcpNoDelay(on: Boolean) { }
                override fun connect(endpoint: SocketAddress, timeout: Int) {
                    entered.complete(Unit); closed.await(); throw SocketException("cancelled")
                }
                override fun close() { closed.countDown() }
            }
        })
        val job = launch { probe.measure("fixture", 80, 1, { listOf(ip) }) }
        entered.await(); withTimeout(1000) { job.cancelAndJoin() }
        assertEquals(0, closed.count)
    }
    @Test fun entireSuspendedProbeConcurrencyIsBounded() = runBlocking {
        val active = AtomicInteger(); val peak = AtomicInteger()
        coroutineScope {
            (1..12).map { index -> async {
                val probe = TcpRttProbe(socketFactory = { object : Socket() {
                    override fun setTcpNoDelay(on: Boolean) { }
                    override fun connect(endpoint: SocketAddress, timeout: Int) { }
                } })
                probe.measure("fixture$index", 80, 1, {
                    peak.updateAndGet { maxOf(it, active.incrementAndGet()) }
                    try { delay(30); listOf(ip) } finally { active.decrementAndGet() }
                })
            } }.awaitAll()
        }
        assertTrue(peak.get() in 1..4); assertEquals(0, active.get())
    }
    @Test fun failedNetworkBindingNeverStartsConnect() = runBlocking {
        var connected = false
        val probe = TcpRttProbe(socketFactory = { object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) { connected = true }
        } })
        try { probe.measure("fixture", 80, 1, { listOf(ip) }, { throw IOException("protect rejected") }); fail() }
        catch (_: IOException) { }
        assertFalse(connected)
    }
    @Test(timeout = 12000) fun ownedDnsTimeoutIsFailureWhileCallerCancellationRemainsCancellation() = runBlocking {
        val started = System.nanoTime()
        try { TcpRttProbe().measure("fixture", 80, 1, { awaitCancellation() }); fail() }
        catch (_: SocketTimeoutException) { }
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertTrue(elapsed in 7900..11000)
    }
}
