// @author 雾晚
package io.nekohasekai.sagernet

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source contracts protect wanBox branches during selective upstream sync; not device tests. */
class NetworkLifecyclePreservationTest {
    private fun source(path: String) = File("src/main/java/io/nekohasekai/sagernet/$path").readText()

    @Test fun firstNetworkAndOldLostProtectionRemain() {
        val network = source("utils/DefaultNetworkListener.kt")
        assertTrue(network.contains("message.processed.await()"))
        assertTrue(network.contains("if (listeners.isEmpty()) register()"))
        assertTrue(network.contains("listeners.remove(message.key) != null && listeners.isEmpty()"))
        val lost = network.substringAfter("is NetworkMessage.Lost ->").substringBefore("/**")
        assertTrue(lost.contains("if (network == message.network)"))
        assertTrue(network.contains("HandlerThread(\"DefaultNetworkListener\")"))
        assertTrue(network.contains("now - lastUpdateTime < 500"))
        assertTrue(network.contains("callbackHandler.removeCallbacks(it)"))
    }

    @Test fun apiFallbackAndServiceCleanupRemain() {
        val network = source("utils/DefaultNetworkListener.kt")
        listOf("Build.VERSION.SDK_INT == 23", "in 24 until 26", "in 26 until 28", "in 28 until 31", "in 31..Int.MAX_VALUE", "fallback = true").forEach {
            assertTrue(it, network.contains(it))
        }
        val service = source("bg/BaseService.kt")
        assertTrue(service.contains("DefaultNetworkListener.stop(this)"))
        assertTrue(service.contains("current.service as? RootTunService"))
        assertTrue(service.contains("service.urlTest(target, timeout)"))
        assertTrue(source("bg/VpnService.kt").contains("setUnderlyingNetworks"))
    }
}
