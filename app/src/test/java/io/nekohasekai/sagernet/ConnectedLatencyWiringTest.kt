// @author 雾晚
package io.nekohasekai.sagernet

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Complements behavioral routing/state tests with checks of their Android integration points. */
class ConnectedLatencyWiringTest {
    private fun source(path: String) = File("src/main/java/io/nekohasekai/sagernet/$path.kt").readText()
    // Reopening, reattaching and IP refresh only render state; probes require a user action. @author 雾晚
    @Test fun reopeningAndStatusRefreshNeverScheduleLatencyProbes() {
        val stats = source("widget/StatsBar")
        assertFalse("Lifecycle/state/IP/cancel callbacks must not silently probe", stats.contains("testConnection(silent = true)"))
        assertFalse(stats.substringAfter("override fun onAttachedToWindow()").substringBefore("fun onIpDetailClicked()").contains("testConnection("))
        assertFalse(stats.substringAfter("fun changeState(").substringBefore("fun refreshLandingIp(").contains("testConnection("))
        assertFalse(stats.substringAfter("fun refreshLandingIp(").substringBefore("fun updateSpeed(").contains("testConnection("))
        assertFalse(stats.substringAfter("job.invokeOnCompletion").contains("testConnection("))
        assertTrue(stats.substringAfter("fun retestLatencyInPlace()").substringBefore("override fun setOnClickListener").contains("testConnection(silent = false)"))
        assertTrue(source("ui/MainActivity").substringAfter("binding.stats.setOnClickListener").substringBefore("binding.stats.setOnLongClickListener").contains("binding.stats.testConnection()"))
    }
    @Test fun binderDispatchesFromOwningServiceBeforeLazyBoxAndKeepsDefaultCustomPaths() {
        val binder = source("bg/BaseService").substringAfter("private fun measureUrl(")
            .substringBefore("override fun postNotificationSpeed")
        assertTrue(binder.contains("current.service as? RootTunService"))
        assertFalse(binder.contains("proxy?.service"))
        assertTrue(binder.indexOf("ConnectedUrlTest.measure") < binder.indexOf("current.proxy?.box"))
        assertTrue(binder.contains("current.state.connected, url, timeoutMs"))
        assertTrue(binder.contains("Libcore.urlTestFull(box, target, timeout)"))
        assertTrue(binder.contains("Libcore.urlTest(box, target, timeout)"))
        assertTrue(source("bg/BaseService").contains("DataStore.connectionTestURL, DataStore.connectionTestTimeout, false"))
        assertTrue(source("bg/BaseService").contains("measureUrl(url, timeoutMs, true)"))
    }
    @Test fun rootReadinessGuardsBothMixedProbeAndIsolatedFallback() {
        val root = source("bg/RootTunService").substringAfter("fun urlTest(").substringBefore("override suspend fun startProcesses")
        assertTrue(root.contains("RootModuleClient.call(\"status\")"))
        assertTrue(root.contains("if (!before.connected)"))
        assertTrue(root.contains("after.runningRevision == before.runningRevision"))
        assertTrue(root.contains("TestInstance(profile, url, timeoutMs).doTest()"))
        assertTrue(root.contains("ProxyUrlProbe.measure("))
        assertFalse(root.contains("proxy?.box"))
    }
    @Test fun statsBindsCapturedServiceAndCancelsWithoutRandomOrStaleSuccess() {
        val stats = source("widget/StatsBar")
        assertFalse(stats.contains("Random.nextInt"))
        assertTrue(stats.contains("activity.urlTest(service)"))
        assertTrue(stats.contains("latencyState.complete(ticket, probeKey(), 0,"))
        assertTrue(stats.contains("job.invokeOnCompletion"))
        assertTrue(stats.contains("override fun onDetachedFromWindow()"))
        assertTrue(stats.contains("DataStore.connectionTestURL, DataStore.connectionTestTimeout"))
        assertTrue(source("ui/MainActivity").contains("Key.CONNECTION_TEST_URL, Key.CONNECTION_TEST_TIMEOUT -> binding.stats.refreshDisplay()"))
    }
}
