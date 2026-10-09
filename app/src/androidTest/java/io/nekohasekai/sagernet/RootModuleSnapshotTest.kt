// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.RootModuleSnapshot
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real App producer without root authorization or starting TUN. @author 雾晚 */
class RootModuleSnapshotTest {
    @Test fun savedRuleTargetChangesReachActualPortableSnapshot() = runBlocking {
        val original = BackupRestore.parse(org.json.JSONObject(io.nekohasekai.sagernet.utils.BackupHelper.doBackup().toString(Charsets.UTF_8)))
        fun node(id: Long, host: String) = ProxyEntity(id = id, groupId = 99010).putBean(SOCKSBean().apply {
            initializeDefaultValues(); serverAddress = host; serverPort = 1080
        })
        val main = node(99011, "192.0.2.1")
        val first = node(99012, "192.0.2.2")
        val second = node(99013, "192.0.2.3")
        val rule = RuleEntity(id = 99014, userOrder = 1, enabled = true, domains = "domain:fixture.invalid", outbound = first.id)
        val remoteFallback = RuleEntity(id = 99015, userOrder = 0, enabled = true,
            ruleset = "rssite:https://example.invalid/list.srs", outbound = -1)
        try {
            BackupRestore.apply(BackupRestore.Plan(listOf(main, first, second), listOf(ProxyGroup(id = 99010, name = "fixture")), listOf(rule, remoteFallback), null), true, true, false)
            DataStore.globalCustomConfig = ""; DataStore.globalMode = false; DataStore.enableClashAPI = false
            suspend fun config(): com.google.gson.JsonObject {
                val instance = ProxyInstance(main)
                try { instance.init(); return RootModuleSnapshot(instance, "fixture-revision").build()["config"].asJsonObject }
                finally { instance.close() }
            }
            fun target(config: com.google.gson.JsonObject): String = config["route"].asJsonObject["rules"].asJsonArray
                .first { it.asJsonObject["domain_suffix"]?.asJsonArray?.any { value -> value.asString == "fixture.invalid" } == true }
                .asJsonObject["outbound"].asString
            fun host(config: com.google.gson.JsonObject, tag: String) = config["outbounds"].asJsonArray
                .first { it.asJsonObject["tag"]?.asString == tag }.asJsonObject["server"].asString
            val before = config()
            val routeRules = before["route"].asJsonObject["rules"].asJsonArray.toList()
            val explicitIndex = routeRules.indexOfFirst { it.asJsonObject["domain_suffix"]?.toString()?.contains("fixture.invalid") == true }
            val remoteIndex = routeRules.indexOfFirst { it.asJsonObject["rule_set"] != null }
            assertTrue("Explicit user rule must precede its remote fallback", explicitIndex >= 0 && remoteIndex > explicitIndex)
            val privateIndex = routeRules.indexOfFirst { it.asJsonObject["ip_is_private"]?.asBoolean == true }
            if (privateIndex >= 0) assertTrue("Private fallback must not shadow user rules", privateIndex > remoteIndex)
            assertEquals("192.0.2.2", host(before, target(before)))
            SagerDatabase.rulesDao.updateRule(rule.copy(outbound = second.id))
            val after = config()
            assertEquals("192.0.2.3", host(after, target(after)))
            assertNotEquals(before.toString(), after.toString())
            SagerDatabase.rulesDao.updateRule(rule.copy(enabled = false))
            assertFalse(config()["route"].asJsonObject["rules"].toString().contains("fixture.invalid"))
        } finally { BackupRestore.apply(original, true, true, true) }
    }
    @Test fun actualGeneratorExportsPortableRootSnapshotAndPreservesDatabase() = runBlocking {
        val settings = PublicDatabase.kvPairDao.all()
        val rules = SagerDatabase.rulesDao.allRules()
        val bean = SOCKSBean().apply { initializeDefaultValues(); serverAddress = "192.0.2.1"; serverPort = 1080 }
        val instance = ProxyInstance(ProxyEntity(id = 99001, groupId = 99002).putBean(bean))
        try {
            DataStore.globalCustomConfig = ""
            DataStore.enableClashAPI = false
            DataStore.serviceMode = "vpn" // Historical preference normalizes to Root, no VPN authorization.
            instance.init()
            val snapshot = RootModuleSnapshot(instance, "").build()
            assertEquals(1, snapshot["schemaVersion"].asInt)
            assertEquals(99001L, snapshot["profileId"].asLong)
            assertEquals(Key.MODE_ROOT, DataStore.serviceMode)
            assertTrue(snapshot["files"].isJsonObject)
            assertTrue(snapshot["plugins"].isJsonArray)
            val config = snapshot["config"].toString()
            assertFalse(config.contains("/data/user/")); assertFalse(config.contains("/data/data/"))
            assertTrue(snapshot["config"].asJsonObject["inbounds"].asJsonArray.any {
                it.asJsonObject["type"]?.asString == "tun" && it.asJsonObject["auto_route"].asBoolean
            })
            assertEquals(rules, SagerDatabase.rulesDao.allRules())
        } finally {
            instance.close()
            BackupRestore.apply(BackupRestore.Plan(null, null, null, settings), false, false, true)
        }
    }
}
