// @author 雾晚
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.route.InputMethodDirectPolicy
import io.nekohasekai.sagernet.route.InputMethodRouteMigration
import libcore.Libcore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real generator and pinned core validation; does not claim speech-network device coverage. @author 雾晚 */
class InputMethodDirectConfigTest {
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
    private val identity = InputMethodDirectPolicy.identity("fixture.keyboard/.Ime", 210123, 10101)!!
    private fun matches(rule: JSONObject): Boolean =
        rule.optJSONArray("package_name")?.optString(0) == identity.packageName ||
            rule.optJSONArray("rules")?.objects()?.any(::matches) == true

    @Test fun migrationIsIdempotentAndGeneratedRulesUseExistingVpnRootRouting() {
        val settings = PublicDatabase.kvPairDao.all()
        val previousRules = SagerDatabase.rulesDao.allRules()
        val bean = SOCKSBean().apply { initializeDefaultValues(); serverAddress = "192.0.2.1"; serverPort = 1080 }
        val proxy = ProxyEntity(id = 99001, groupId = 99002).putBean(bean)
        try {
            BackupRestore.apply(BackupRestore.Plan(null, null, listOf(
                RuleEntity(id = 99009, userOrder = 1, enabled = true, domains = "full:fixture.user.invalid", outbound = -1)
            ), null), false, true, false)
            DataStore.globalCustomConfig = ""
            DataStore.inputMethodDirect = true
            InputMethodRouteMigration.migrate(identity, "Fixture keyboard", true)
            val imported = SagerDatabase.rulesDao.allRules().single { it.packages == setOf(identity.packageName) }
            assertTrue(imported.enabled)
            assertEquals(-1L, imported.outbound)
            assertNull(DataStore.configurationStore.getBoolean(Key.INPUT_METHOD_DIRECT))
            // A retry after interruption between the two databases cannot create duplicates.
            DataStore.inputMethodDirect = true
            InputMethodRouteMigration.migrate(identity, "Other locale name", true)
            assertEquals(imported, SagerDatabase.rulesDao.getById(imported.id))
            assertEquals(2, SagerDatabase.rulesDao.allRules().size)
            DataStore.globalMode = false
            DataStore.enableDnsRouting = true
            for (mode in listOf("vpn", Key.MODE_ROOT)) for (fakeIp in listOf(false, true)) {
                    DataStore.serviceMode = mode; DataStore.enableFakeDns = fakeIp
                    val result = buildConfig(proxy).config
                    val config = JSONObject(result)
                    val routes = config.getJSONObject("route").getJSONArray("rules").objects()
                    val index = routes.indexOfFirst(::matches)
                    assertTrue(index >= 0)
                    assertEquals("bypass", routes[index].getString("outbound"))
                    assertTrue(routes.indexOfFirst { it.optString("action") == "hijack-dns" } < index)
                    val userIndex = routes.indexOfFirst { it.optJSONArray("domain")?.optString(0) == "fixture.user.invalid" }
                    assertTrue(userIndex > index)
                    // No addDisallowedApplication/exclude_uid: Fake-IP must still be handled in TUN.
                    val tun = config.getJSONArray("inbounds").objects().single { it.optString("type") == "tun" }
                    assertFalse(tun.optJSONArray("exclude_uid")?.toString()?.contains("210123") == true)
                    val dnsRules = config.getJSONObject("dns").getJSONArray("rules").objects()
                    val dnsIndex = dnsRules.indexOfFirst(::matches)
                    assertTrue(dnsIndex >= 0)
                    assertEquals("dns-direct", dnsRules[dnsIndex].getString("server"))
                    val fakeIndex = dnsRules.indexOfFirst { it.optString("server") == "dns-fake" }
                    if (fakeIndex >= 0) assertTrue(dnsIndex < fakeIndex)
                    val server = config.getJSONObject("dns").getJSONArray("servers").objects().single { it.optString("tag") == "dns-direct" }
                    assertEquals("direct", server.getString("detour"))
                    Libcore.newTestSingBoxInstance(result, null).close()
                }
            // Rule disable/delete stays effective; neither migration nor hidden config recreates it.
            SagerDatabase.rulesDao.updateRule(imported.copy(enabled = false))
            DataStore.inputMethodDirect = true
            InputMethodRouteMigration.migrate(identity, "Fixture", true)
            assertFalse(SagerDatabase.rulesDao.getById(imported.id)!!.enabled)
            assertFalse(JSONObject(buildConfig(proxy).config).getJSONObject("route").getJSONArray("rules").objects().any(::matches))
            SagerDatabase.rulesDao.deleteById(imported.id)
            InputMethodRouteMigration.migrate()
            assertEquals(1, SagerDatabase.rulesDao.allRules().size)
            // An explicitly disabled old preference is migrated as a disabled ordinary rule.
            DataStore.inputMethodDirect = false
            InputMethodRouteMigration.migrate(identity, "Fixture", false)
            assertFalse(SagerDatabase.rulesDao.allRules().single { it.packages == setOf(identity.packageName) }.enabled)
        } finally {
            BackupRestore.apply(BackupRestore.Plan(null, null, previousRules, settings), false, true, true)
        }
    }
}
