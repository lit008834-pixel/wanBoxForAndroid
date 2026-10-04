// @author 雾晚
package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

/** Guards existing IP preference and TLS identity through the DNS runner change. @author 雾晚 */
class SubscriptionAddressRewriteTest {
    private val updater = object : GroupUpdater() {
        override suspend fun doUpdate(
            proxyGroup: ProxyGroup, subscription: SubscriptionBean,
            userInterface: GroupManager.Interface?, byUser: Boolean
        ) = error("Fixture does not update subscriptions")

        fun apply(bean: HttpBean, addresses: List<InetAddress>, ipv6First: Boolean) =
            rewriteAddress(bean, addresses, ipv6First)
    }
    private val ipv4 = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1))
    private val ipv6 = InetAddress.getByAddress(byteArrayOf(
        0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1
    ))

    @Test fun ipv4PreferenceKeepsOriginalHostnameAsTlsIdentity() {
        val bean = HttpBean().apply {
            serverAddress = "fixture.example"; security = "tls"; sni = ""
        }
        updater.apply(bean, listOf(ipv6, ipv4), false)
        assertEquals(ipv4.hostAddress, bean.serverAddress)
        assertEquals("fixture.example", bean.sni)
    }

    @Test fun ipv6PreferencePreservesExplicitTlsIdentity() {
        val bean = HttpBean().apply {
            serverAddress = "fixture.example"; security = "tls"; sni = "identity.example"
        }
        updater.apply(bean, listOf(ipv4, ipv6), true)
        assertEquals(ipv6.hostAddress, bean.serverAddress)
        assertEquals("identity.example", bean.sni)
    }
}
