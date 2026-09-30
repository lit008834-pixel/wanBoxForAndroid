// @author 雾晚
package io.nekohasekai.sagernet.group

import org.junit.Assert.*
import org.junit.Test
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import moe.matsuri.nb4a.Protocols

class SubscriptionCleanupTest {
    @Test fun noticesAndUnicode() {
        assertTrue(SubscriptionCleanup.isNotice("套餐到期：2027-01-23"))
        assertFalse(SubscriptionCleanup.isNotice("日本 电信 01"))
        assertEquals("日本 • 电信 01", SubscriptionCleanup.cleanName("🇯🇵 日本 • 电信 01 ✨"))
    }

    @Test fun sameEndpointDifferentTransportIsNotDuplicate() {
        val a = VMessBean().apply {
            initializeDefaultValues()
            serverAddress = "example.com"
            serverPort = 443
            uuid = "00000000-0000-4000-8000-000000000001"
            name = "first"
        }
        val b = a.clone().apply { name = "renamed" }
        fun hash(bean: VMessBean) = Protocols.Deduplication(bean, bean.javaClass.name).hash()
        assertEquals(hash(a), hash(b))
        b.type = "ws"
        assertNotEquals(hash(a), hash(b))
        b.type = a.type
        b.realityShortId = "abcd"
        assertNotEquals(hash(a), hash(b))
    }
}
