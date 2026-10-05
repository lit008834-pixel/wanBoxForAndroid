// @author 雾晚
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.route.InputMethodDirectPolicy
import libcore.Libcore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real generator and pinned core validation; does not claim speech-network device coverage. @author 雾晚 */
class InputMethodDirectConfigTest {
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private val identity = InputMethodDirectPolicy.identity("fixture.keyboard/.Ime", 210123, 10101)!!
    private fun matches(rule: JSONObject) = rule.optString("type") == "logical" &&
        rule.optJSONArray("rules")?.objects()?.any {
            it.optJSONArray("package_name")?.optString(0) == identity.packageName
        } == true

    @Test fun vpnRootFakeIpAndGlobalModeUseDirectWithoutChangingTunFiltersOrFinal() {
        val settings = PublicDatabase.kvPairDao.all()
        val previousRules = SagerDatabase.rulesDao.allRules()
        val bean = SOCKSBean().apply { initializeDefaultValues(); serverAddress = "192.0.2.1"; serverPort = 1080 }
        val proxy = ProxyEntity(id = 99001, groupId = 99002).putBean(bean)
        try {
            BackupRestore.apply(BackupRestore.Plan(null, null, listOf(
                RuleEntity(id = 99009, userOrder = 1, enabled = true, domains = "full:fixture.user.invalid", outbound = -1)
            ), null), false, true, false)
            DataStore.globalCustomConfig = ""
            for (mode in listOf(Key.MODE_VPN, Key.MODE_ROOT))
                for (fakeIp in listOf(false, true)) for (global in listOf(false, true)) {
                    DataStore.serviceMode = mode; DataStore.enableFakeDns = fakeIp; DataStore.globalMode = global
                    DataStore.inputMethodDirect = false
                    val original = JSONObject(buildConfig(proxy, false, false, identity).config)
                    assertFalse(original.getJSONObject("route").getJSONArray("rules").objects().any(::matches))
                    DataStore.inputMethodDirect = true
                    val result = buildConfig(proxy, false, false, identity).config
                    val config = JSONObject(result)
                    val routes = config.getJSONObject("route").getJSONArray("rules").objects()
                    val index = routes.indexOfFirst(::matches)
                    assertTrue(index >= 0)
                    assertEquals("direct", routes[index].getString("outbound"))
                    assertEquals(210123, routes[index].getJSONArray("rules").getJSONObject(1).getJSONArray("user_id").getInt(0))
                    assertTrue(routes.indexOfFirst { it.optString("action") == "hijack-dns" } < index)
                    val userIndex = routes.indexOfFirst { it.optJSONArray("domain")?.optString(0) == "fixture.user.invalid" }
                    if (!global) assertTrue(userIndex > index)
                    assertEquals(original.getJSONObject("route").optString("final"), config.getJSONObject("route").optString("final"))
                    // No addDisallowedApplication/exclude_uid: Fake-IP must still be handled in TUN.
                    val originalTun = original.getJSONArray("inbounds").objects().single { it.optString("type") == "tun" }
                    val tun = config.getJSONArray("inbounds").objects().single { it.optString("type") == "tun" }
                    assertEquals(originalTun.toString(), tun.toString())
                    val dnsRules = config.getJSONObject("dns").getJSONArray("rules").objects()
                    val dnsIndex = dnsRules.indexOfFirst(::matches)
                    assertTrue(dnsIndex >= 0)
                    assertEquals("dns-direct", dnsRules[dnsIndex].getString("server"))
                    assertTrue(dnsRules[dnsIndex].getBoolean("disable_cache"))
                    val fakeIndex = dnsRules.indexOfFirst { it.optString("server") == "dns-fake" }
                    if (fakeIndex >= 0) assertTrue(dnsIndex < fakeIndex)
                    val server = config.getJSONObject("dns").getJSONArray("servers").objects().single { it.optString("tag") == "dns-direct" }
                    assertEquals("direct", server.getString("detour"))
                    Libcore.newTestSingBoxInstance(result, null).close()
                }
            DataStore.inputMethodDirect = true
            for (mode in listOf(Key.MODE_VPN, Key.MODE_ROOT, Key.MODE_PROXY)) {
                DataStore.serviceMode = mode
                for ((test, export, input) in listOf(Triple(true, false, identity), Triple(false, true, identity), Triple(false, false, null))) {
                    val config = JSONObject(buildConfig(proxy, test, export, input).config)
                    assertFalse(config.getJSONObject("route").getJSONArray("rules").objects().any(::matches))
                    assertFalse(config.getJSONObject("dns").getJSONArray("rules").objects().any(::matches))
                }
                if (mode == Key.MODE_PROXY) {
                    assertFalse(JSONObject(buildConfig(proxy, false, false, identity).config)
                        .getJSONObject("route").getJSONArray("rules").objects().any(::matches))
                }
            }
        } finally {
            BackupRestore.apply(BackupRestore.Plan(null, null, previousRules, settings), false, true, true)
        }
    }
}
