// @author 雾晚
package io.nekohasekai.sagernet

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Protect runtime boundaries during selective upstream sync; not device measurements. @author 雾晚 */
class RuntimeTelemetryContractTest {
    private fun source(path: String) = File("src/main/java/io/nekohasekai/sagernet/$path").readText()

    @Test fun automaticLifecycleEventsDoNotForceGarbageCollection() {
        listOf("SagerNet.kt", "ui/MainActivity.kt", "bg/BaseService.kt", "bg/proto/TrafficLooper.kt").forEach {
            val code = source(it)
            assertFalse(it, code.contains("System.gc()"))
            assertFalse(it, code.contains("Libcore.forceGc()"))
        }
        val service = source("bg/BaseService.kt")
        assertTrue(service.contains("Intent.ACTION_SCREEN_OFF ->")) // must not fall through to stopRunner
        assertTrue(service.contains("proxy?.box }.getOrNull()?.wake()"))
        assertTrue(service.contains("DataStore.wakeResetConnections"))
        assertTrue(source("SagerNet.kt").contains("cleanWebview()"))
        assertTrue(source("ui/MainActivity.kt").contains("CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND"))
        val core = File("../libcore/box.go").readText()
        assertFalse(core.contains("runtime.GC()"))
        assertFalse(core.contains("debug.FreeOSMemory()"))
        assertTrue(core.contains("b.cancel()"))
        assertTrue(core.contains("b.Box.Close()"))
    }

    @Test fun unreadyCoreSuspendsAndStaticMembershipIsNotRebuiltEverySample() {
        val loop = source("bg/proto/TrafficLooper.kt")
        assertFalse(loop.contains("if (!proxy.isInitialized()) continue"))
        assertTrue(loop.contains("TrafficPollPolicy.ready("))
        assertTrue(loop.contains("TrafficPollPolicy.intervalMs("))
        assertTrue(loop.contains("balancerMemberIds = proxy.config.balancerMemberMap"))
        assertFalse(loop.contains("val balancerMemberIds = proxy.config.balancerMemberMap"))
        assertTrue(loop.contains("job?.cancelAndJoin()"))
        assertTrue(loop.contains("currentCoroutineContext().ensureActive()"))
    }

    @Test fun vpnReportsPerInstanceTunHostsForApplicationRouteLookups() {
        val bridge = File("../libcore/platform_box.go").readText()
        assertTrue(bridge.contains("myTunAddress      []netip.Addr"))
        assertTrue(bridge.contains("tunInterfaceAddresses(options.Inet4Address, options.Inet6Address)"))
        assertTrue(bridge.contains("return w.myTunAddress"))
        assertTrue(bridge.contains("UserId: uid, PackageNames: packageNames"))
        // Root continues to use its standalone core; this does not add app-process ownership.
        val root = source("bg/RootTunService.kt")
        assertTrue(root.contains("RootModuleClient.call(\"status\")"))
        assertFalse(root.contains("launchExternalOnly"))
    }
}
