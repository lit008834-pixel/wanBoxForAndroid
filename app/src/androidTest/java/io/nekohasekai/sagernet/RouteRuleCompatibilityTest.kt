// @author 雾晚
package io.nekohasekai.sagernet

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.route.RouteRuleEditor
import libcore.Libcore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RouteRuleCompatibilityTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        SagerDatabase::class.java.canonicalName!!, FrameworkSQLiteOpenHelperFactory())
    @Test fun oldRulesSurviveHistoricalMigrationWithEveryColumn() {
        val name = "route-rule-v9-upgrade"
        helper.createDatabase(name, 9).apply {
            execSQL("INSERT INTO rules (id,name,config,userOrder,enabled,domains,ip,port,sourcePort,network,source,protocol,ruleset,outbound,packages) VALUES (81,'old','{\"future_match\":7}',37,1,'domain:example.com','192.0.2.0/24','443','1000:2000','tcp','10.0.0.0/8','tls','',-1,'com.example.app')")
            close()
        }
        val db = Room.databaseBuilder(context, SagerDatabase::class.java, name).addMigrations(SagerDatabase.MIGRATION_9_10).build()
        try {
            val rule = db.rulesDao().getById(81)!!
            assertEquals(10, db.openHelper.writableDatabase.version)
            assertEquals(81L, rule.id); assertEquals(37L, rule.userOrder); assertTrue(rule.enabled)
            assertEquals(-1L, rule.outbound); assertEquals("domain:example.com", rule.domains)
            assertEquals("192.0.2.0/24", rule.ip); assertEquals("443", rule.port)
            assertEquals("1000:2000", rule.sourcePort); assertEquals("tcp", rule.network)
            assertEquals("10.0.0.0/8", rule.source); assertEquals("tls", rule.protocol)
            assertEquals(setOf("com.example.app"), rule.packages); assertEquals("{\"future_match\":7}", rule.config)
        } finally { db.close() }
    }
    @Test fun legacyParcelAndNewAdvancedBackupRoundTripWithoutChangingFormat() {
        for (config in listOf("", "{\"domain_regex\":[\"a{1,3}\\\\.example\"],\"invert\":true,\"future_match\":7}")) {
            val old = RuleEntity(id = 82, userOrder = 9, enabled = true, outbound = -1,
                packages = setOf("com.example.app"), config = config, name = "kept")
            val parcel = android.os.Parcel.obtain()
            val encoded = try { old.writeToParcel(parcel, 0); android.util.Base64.encodeToString(parcel.marshall(), android.util.Base64.NO_WRAP) }
                finally { parcel.recycle() }
            val parsed = BackupRestore.parse(JSONObject().put("version", 1).put("rules", JSONArray().put(encoded))).rules!!.single()
            assertEquals(old, parsed)
        }
    }
    @Test fun realPinnedCoreChecksActionsAndRejectsRe2AndSchemaErrorsWithoutStartingService() {
        for (config in listOf("", "{\"action\":\"reject\",\"method\":\"reply\",\"no_drop\":true}",
            "{\"action\":\"sniff\",\"sniffer\":[\"tls\",\"http\"]}", "{\"action\":\"resolve\",\"strategy\":\"ipv4_only\"}",
            "{\"action\":\"route-options\",\"override_port\":443}", "{\"action\":\"hijack-dns\"}",
            "{\"domain_regex\":[\"a{1,3}\\\\.example\"]}", "{\"ip_version\":6,\"source_ip_is_private\":true}")) {
            val box = Libcore.newTestSingBoxInstance(RouteRuleEditor.validationConfig(RuleEntity(domains = "example.com", config = config)), null)
            box.close()
        }
        for (config in listOf("{\"domain_regex\":[\"(?<=a)b\"]}", "{\"action\":\"reject\",\"method\":\"drop\",\"no_drop\":true}",
            "{\"action\":\"sniff\",\"override_destination\":true}", "{\"unknown_match_field\":true}")) {
            try {
                Libcore.newTestSingBoxInstance(RouteRuleEditor.validationConfig(RuleEntity(domains = "example.com", config = config)), null).close()
                fail("Core accepted $config")
            } catch (_: Exception) {}
        }
    }
    @Test fun realGeneratorKeepsOrderTargetsAndFieldsInVpnAndRootTun() {
        val previousRules = SagerDatabase.rulesDao.allRules()
        val previousSettings = io.nekohasekai.sagernet.database.preference.PublicDatabase.kvPairDao.all()
        val previousMode = DataStore.serviceMode
        val previousGlobal = DataStore.globalMode
        val bean = io.nekohasekai.sagernet.fmt.socks.SOCKSBean().apply {
            initializeDefaultValues(); serverAddress = "192.0.2.1"; serverPort = 1080
        }
        val proxy = ProxyEntity(id = 99001, groupId = 99002).putBean(bean)
        var selectedTargetId = 0L
        var rules = listOf(
            RuleEntity(id = 901, userOrder = 1, enabled = true, domains = "full:first.route.invalid", outbound = -1),
            RuleEntity(id = 902, userOrder = 2, enabled = true, domains = "full:second.route.invalid", config = "{\"action\":\"sniff\",\"sniffer\":[\"tls\"]}"),
            RuleEntity(id = 903, userOrder = 3, enabled = true, ip = "192.0.2.0/24", config = "{\"action\":\"reject\",\"method\":\"reply\"}"),
            RuleEntity(id = 904, userOrder = 4, enabled = true, packages = setOf(context.packageName), outbound = -1),
            RuleEntity(id = 905, userOrder = 5, enabled = true, ruleset = "rssite:https://example.com/rules.srs"),
            RuleEntity(id = 906, userOrder = 6, enabled = true, domains = "full:disabled.route.invalid", outbound = -1).apply { enabled = false },
        )
        try {
            val targetBean = io.nekohasekai.sagernet.fmt.socks.SOCKSBean().apply {
                initializeDefaultValues(); serverAddress = "192.0.2.2"; serverPort = 1081
            }
            selectedTargetId = SagerDatabase.proxyDao.addProxy(ProxyEntity(groupId = 99002).putBean(targetBean))
            rules = rules + RuleEntity(id = 907, userOrder = 7, enabled = true,
                packages = setOf(InstrumentationRegistry.getInstrumentation().context.packageName), outbound = selectedTargetId)
            BackupRestore.apply(BackupRestore.Plan(null, null, rules, null), false, true, false)
            DataStore.globalMode = false
            for (mode in listOf(Key.MODE_VPN, Key.MODE_ROOT)) for (fakeDns in listOf(false, true)) {
                DataStore.enableFakeDns = fakeDns
                DataStore.serviceMode = mode
                val root = JSONObject(io.nekohasekai.sagernet.fmt.buildConfig(proxy).config)
                val generated = root.getJSONObject("route").getJSONArray("rules")
                val list = (0 until generated.length()).map { generated.getJSONObject(it) }
                val first = list.indexOfFirst { it.optJSONArray("domain")?.toString()?.contains("first.route.invalid") == true }
                val second = list.indexOfFirst { it.optJSONArray("domain")?.toString()?.contains("second.route.invalid") == true }
                assertTrue(first >= 0); assertTrue(second > first)
                assertEquals("bypass", list[first].getString("outbound"))
                assertEquals("sniff", list[second].getString("action")); assertFalse(list[second].has("outbound"))
                val rejected = list.first { it.optString("method") == "reply" }
                assertEquals("reject", rejected.getString("action")); assertFalse(rejected.has("outbound"))
                val appRule = list.first { it.optString("type") == "logical" && it.toString().contains(context.packageName) }
                assertEquals("or", appRule.getString("mode"))
                assertEquals("bypass", appRule.getString("outbound"))
                assertTrue(appRule.getJSONArray("rules").getJSONObject(0).has("package_name"))
                assertTrue(appRule.getJSONArray("rules").getJSONObject(1).has("user_id"))
                val selectedRule = list.first { it.optString("type") == "logical" &&
                    it.getJSONArray("rules").getJSONObject(0).optJSONArray("package_name")?.toString()?.contains(InstrumentationRegistry.getInstrumentation().context.packageName) == true }
                val chosenTag = selectedRule.getString("outbound")
                val generatedOutbounds = root.getJSONArray("outbounds")
                val chosen = (0 until generatedOutbounds.length()).map { generatedOutbounds.getJSONObject(it) }
                    .first { it.optString("tag") == chosenTag }
                assertEquals("192.0.2.2", chosen.getString("server"))
                assertEquals(1081, chosen.getInt("server_port"))
                val dnsRules = root.getJSONObject("dns").getJSONArray("rules")
                val selectedDns = (0 until dnsRules.length()).map { dnsRules.getJSONObject(it) }
                    .first { it.optString("type") == "logical" &&
                        it.getJSONArray("rules").getJSONObject(0).optJSONArray("package_name")?.toString()?.contains(InstrumentationRegistry.getInstrumentation().context.packageName) == true }
                assertEquals(if (fakeDns) "dns-fake" else "dns-remote", selectedDns.getString("server"))
                assertTrue(list.none { it.toString().contains("disabled.route.invalid") })
                // @author 雾晚: compare decoded URL values; Android JSONObject escapes slashes.
                val generatedSets = root.getJSONObject("route").getJSONArray("rule_set")
                assertTrue("Remote rule-set URL missing in $mode", (0 until generatedSets.length()).any {
                    generatedSets.getJSONObject(it).optString("url") == "https://example.com/rules.srs"
                })
                assertTrue(list.none { it.has("override_destination") || it.has("reject_method") })
                assertEquals(rules, SagerDatabase.rulesDao.allRules())
            }
            DataStore.globalMode = true
            val global = io.nekohasekai.sagernet.fmt.buildConfig(proxy).config
            assertFalse(global.contains("first.route.invalid")); assertFalse(global.contains("second.route.invalid"))
        } finally {
            if (selectedTargetId > 0) SagerDatabase.proxyDao.deleteById(selectedTargetId)
            DataStore.serviceMode = previousMode; DataStore.globalMode = previousGlobal
            BackupRestore.apply(BackupRestore.Plan(null, null, previousRules, previousSettings), false, true, true)
        }
    }
}
