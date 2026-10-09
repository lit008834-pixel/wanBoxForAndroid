// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.RootModuleSnapshot
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.route.DomesticRoutingPreset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

/** Actual Room, generator and portable snapshot; no real nodes or network requests. @author 雾晚 */
class DomesticRoutingConfigTest {
    @Test fun presetIsRepeatableAndAppOverridesDnsAndGeoFallbacks() = runBlocking {
        val original = BackupRestore.parse(JSONObject(io.nekohasekai.sagernet.utils.BackupHelper.doBackup().toString(Charsets.UTF_8)))
        val main = ProxyEntity(id = 99201, groupId = 99200).putBean(SOCKSBean().apply {
            initializeDefaultValues(); serverAddress = "192.0.2.1"; serverPort = 1080
        })
        val appPackage = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.packageName
        val oldDomain = RuleEntity(id = 99202, name = "old name", userOrder = 1, domains = "geosite-cn", outbound = -1)
        val selectedApp = RuleEntity(id = 99203, userOrder = 3, enabled = true, packages = setOf(appPackage), outbound = 0)
        val explicit = RuleEntity(id = 99204, userOrder = 4, enabled = true, domains = "domain:fixture.invalid", outbound = 0)
        try {
            BackupRestore.apply(BackupRestore.Plan(listOf(main), listOf(ProxyGroup(id = 99200, name = "fixture")),
                listOf(oldDomain, selectedApp, explicit), null), true, true, false)
            DomesticRoutingPreset.apply("domestic", "IPs")
            val once = SagerDatabase.rulesDao.allRules()
            DomesticRoutingPreset.apply("domestic", "IPs")
            assertEquals(once, SagerDatabase.rulesDao.allRules())
            assertEquals(4, once.size)
            assertEquals(oldDomain.copy(enabled = true), once.first { it.id == oldDomain.id })
            assertEquals(selectedApp, once.first { it.id == selectedApp.id })
            DataStore.globalCustomConfig = ""; DataStore.globalMode = false
            DataStore.enableDnsRouting = true; DataStore.proxyApps = false
            DataStore.enableClashAPI = false; DataStore.resolveDestination = false
            for (fakeIp in listOf(false, true)) {
                DataStore.enableFakeDns = fakeIp
                val instance = ProxyInstance(main)
                try {
                    instance.init()
                    val snapshot = RootModuleSnapshot(instance, "fixture-revision").build()
                    val config = snapshot["config"].asJsonObject
                    val routes = config["route"].asJsonObject["rules"].asJsonArray.toList().map { it.asJsonObject }
                    val app = routes.indexOfFirst { it["rules"]?.toString()?.contains(appPackage) == true }
                    val domain = routes.indexOfFirst { it["rule_set"]?.toString()?.contains("geosite-cn") == true }
                    val ips = routes.indexOfFirst { it["rule_set"]?.toString()?.contains("geoip:cn") == true }
                    val literal = routes.indexOfFirst { it["domain_suffix"]?.toString()?.contains("fixture.invalid") == true }
                    val resolve = routes.indexOfFirst { it["action"]?.asString == "resolve" }
                    assertTrue(app >= 0 && literal > app && domain > literal)
                    assertTrue(resolve > domain && ips > resolve)
                    assertEquals("3s", routes[resolve]["timeout"].asString)
                    assertTrue(routes.none { it.has("match_only") })
                    assertEquals("bypass", routes[domain]["outbound"].asString)
                    assertEquals("bypass", routes[ips]["outbound"].asString)
                    assertEquals(routes[app]["outbound"].asString, config["route"].asJsonObject["final"].asString)
                    val dns = config["dns"].asJsonObject
                    val dnsRules = dns["rules"].asJsonArray.toList().map { it.asJsonObject }
                    val appDns = dnsRules.indexOfFirst { it["rules"]?.toString()?.contains(appPackage) == true }
                    val domainDns = dnsRules.indexOfFirst { it["rule_set"]?.toString()?.contains("geosite-cn") == true }
                    assertTrue(appDns >= 0 && domainDns > appDns)
                    assertEquals(if (fakeIp) "dns-fake" else "dns-remote", dnsRules[appDns]["server"].asString)
                    assertEquals("dns-direct", dnsRules[domainDns]["server"].asString)
                    assertEquals("dns-remote", dns["final"].asString)
                    assertEquals(once, SagerDatabase.rulesDao.allRules())
                } finally { instance.close() }
            }
            DataStore.globalMode = true
            assertFalse(io.nekohasekai.sagernet.fmt.buildConfig(main).config.contains("geosite-cn"))
        } finally { BackupRestore.apply(original, true, true, true) }
    }
}
