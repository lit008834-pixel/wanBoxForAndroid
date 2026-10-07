// @author 雾晚
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.v2ray.*
import io.nekohasekai.sagernet.utils.SecureNetwork
import moe.matsuri.nb4a.SingBoxOptions
import org.junit.Assert.*
import org.junit.Test

/** Runs against the actual shrunk release as well as debug, with fictional credentials. @author 雾晚 */
class ReleaseSerializationTest {
    @Test fun vmessShareKryoAndPortableBackupPreserveReflectionFields() {
        val bean = VMessBean().apply {
            initializeDefaultValues(); name = "虚构节点"; serverAddress = "192.0.2.10"; serverPort = 443
            uuid = "00000000-0000-0000-0000-000000000001"; type = "tcp"
        }
        val decoded = parseV2RayN(bean.toV2rayN())
        assertEquals(bean.serverAddress, decoded.serverAddress); assertEquals(bean.uuid, decoded.uuid)
        val binary = KryoConverters.deserializeStrict(VMessBean(), KryoConverters.serialize(bean))
        assertEquals(bean.uuid, binary.uuid)
        val profile = ProxyEntity(id = 2, groupId = 1).putBean(bean)
        val plan = BackupRestore.Plan(listOf(profile), listOf(ProxyGroup(id = 1, name = "虚构分组")), emptyList(), null)
        val encoded = org.json.JSONObject(PortableBackup.encode(plan).toString(Charsets.UTF_8))
        assertTrue(encoded.getJSONArray("profiles").getJSONObject(0).getJSONObject("bean").has("serverAddress"))
        val restored = PortableBackup.parse(encoded).profiles!!.single().requireBean() as VMessBean
        assertEquals(bean.uuid, restored.uuid); assertEquals(bean.serverPort, restored.serverPort)
        assertEquals("fixture", VMessBean::class.java.getField("name").run {
            set(restored, "fixture"); get(restored)
        })
    }
    @Test fun nativeConfigurationNamesAndSharedSecureClientSurviveR8() {
        val tun = SingBoxOptions.Inbound_TunOptions().apply { type = "tun"; mtu = 1500 }
        val map = tun.asMap()
        assertEquals("tun", map["type"]); assertEquals(1500.0, (map["mtu"] as Number).toDouble(), 0.0)
        assertFalse(map.containsKey("gso"))
        assertSame(SecureNetwork.webDAVClient(), SecureNetwork.webDAVClient())
        assertFalse(SecureNetwork.webDAVClient().followRedirects)
    }
}
