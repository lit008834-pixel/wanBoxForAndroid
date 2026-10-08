// @author 雾晚
package io.nekohasekai.sagernet

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Complements behavioral tests with production wiring and packaging boundaries. @author 雾晚 */
class Arm64BackgroundContractTest {
    private fun source(path: String) = File("src/main/java/io/nekohasekai/sagernet/$path").readText().replace("\r\n", "\n")
    @Test fun foregroundPromotionDoesNotSwallowFailureAndScreenOffIsTrackedDuringStart() {
        val notification = source("bg/ServiceNotification.kt")
        assertTrue(notification.contains("listenPostSpeed = SagerNet.power.isInteractive"))
        val show = notification.substringAfter("private fun show()").substringBefore("fun destroy()")
        assertTrue(show.contains("startForeground")); assertFalse(show.contains("catch"))
        assertTrue(notification.contains("service.unregisterReceiver(this)\n            throw e"))
        assertTrue(notification.contains("if (!manager.areNotificationsEnabled()) return"))
        val receiver = notification.substringAfter("override fun onReceive").substringBefore("private fun show()")
        assertTrue(receiver.indexOf("listenPostSpeed =") < receiver.indexOf("BaseService.State.Connected"))
    }
    @Test fun scheduledUpdatesUseConstraintsAndOnlyCancelWhenDisabled() {
        val updater = source("bg/SubscriptionUpdater.kt")
        assertTrue(updater.contains("setRequiredNetworkType(NetworkType.CONNECTED)"))
        assertTrue(updater.contains("setRequiresBatteryNotLow(true)"))
        assertTrue(updater.substringAfter("if (subscriptions.isEmpty())").substringBefore("val plan").contains("cancelUniqueWork"))
        assertFalse(updater.substringAfter("val plan").contains("cancelUniqueWork"))
        assertTrue(updater.contains("finally { nm.cancel(2) }"))
    }
    @Test fun tcpPathPreservesCancellationAndDoesNotRewriteChannelHealthOrUdpResults() {
        val tcp = source("bg/proto/TcpPing.kt")
        assertTrue(tcp.contains("DnsResolver.getInstance().query(network"))
        assertTrue(tcp.contains("signal.cancel()")); assertTrue(tcp.contains("network?.bindSocket(socket)"))
        assertFalse(tcp.contains("vpnService")); assertFalse(tcp.contains("UrlTest().doTest"))
        val batch = source("ui/ConfigurationFragment.kt").substringAfter("fun tcpPingTest()").substringBefore("inner class GroupPagerAdapter")
        assertTrue(batch.contains("TcpPing.supports(it)"))
        assertTrue(batch.contains("repeat(DataStore.connectionTestConcurrent.coerceIn(1, 4))"))
        assertTrue(batch.contains("catch (e: kotlinx.coroutines.CancellationException)"))
        assertTrue(batch.contains("profile.ping = 0"))
        val service = source("bg/BaseService.kt")
        assertTrue(service.contains("service.urlTest(target, timeout)"))
        assertTrue(File("src/main/res/values").listFiles()!!.any {
            it.readText().contains(">https://www.gstatic.com/generate_204</string>")
        })
    }
}
