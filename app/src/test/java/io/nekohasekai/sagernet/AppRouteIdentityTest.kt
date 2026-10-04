// @author 雾晚
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.route.AppRouteIdentity
import io.nekohasekai.sagernet.utils.AppUidPackages
import moe.matsuri.nb4a.SingBoxOptions
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Exercises actual serialized route/DNS models, without Android services. @author 雾晚 */
class AppRouteIdentityTest {
    @Test fun packageAndUidBecomeAlternativesWithTheSameTarget() {
        val rule = SingBoxOptions.Rule_DefaultOptions().apply {
            package_name = listOf("example.chat"); user_id = listOf(10123); outbound = "fixture-node"
        }
        val json = JSONObject(AppRouteIdentity.route(rule).asMap())
        assertEquals("logical", json.getString("type")); assertEquals("or", json.getString("mode"))
        assertEquals("fixture-node", json.getString("outbound"))
        val fixture = javaClass.getResource("/app-route-identity.json")!!.readText()
        assertTrue(json.similar(JSONObject(fixture)))
        val branches = json.getJSONArray("rules")
        assertTrue(branches.getJSONObject(0).has("package_name")); assertFalse(branches.getJSONObject(0).has("user_id"))
        assertTrue(branches.getJSONObject(1).has("user_id")); assertFalse(branches.getJSONObject(1).has("package_name"))
        assertFalse(branches.getJSONObject(0).has("outbound")); assertFalse(json.has("domain_suffix"))
    }

    @Test fun otherConditionsAndInvertAreNotBroadened() {
        val raw = JSONObject("""{"package_name":["example.chat"],"user_id":[10123],"network":["udp"],"ip_version":6,"port":[443],"invert":true,"action":"reject","method":"reply","no_drop":true}""")
        val transformed = AppRouteIdentity.transform(raw)
        assertTrue(transformed.getBoolean("invert")); assertEquals("reject", transformed.getString("action"))
        assertEquals("reply", transformed.getString("method")); assertTrue(transformed.getBoolean("no_drop"))
        for (i in 0..1) {
            val child = transformed.getJSONArray("rules").getJSONObject(i)
            assertEquals(6, child.getInt("ip_version")); assertEquals(443, child.getJSONArray("port").getInt(0))
            assertEquals("udp", child.getJSONArray("network").getString(0)); assertFalse(child.has("invert"))
        }
        assertTrue(raw.has("package_name")); assertTrue(raw.has("user_id"))
    }

    @Test fun actionOptionsStayOnOuterRule() {
        val raw = JSONObject("""{"package_name":["example.chat"],"user_id":[10123],"outbound":"fixture-node","udp_timeout":"5m","tls_fragment":true}""")
        val fixed = AppRouteIdentity.transform(raw)
        assertEquals("5m", fixed.getString("udp_timeout")); assertTrue(fixed.getBoolean("tls_fragment"))
        for (i in 0..1) assertFalse(fixed.getJSONArray("rules").getJSONObject(i).has("udp_timeout"))
    }

    @Test fun directOptionsAndUnchangedRuleTypesStayIntact() {
        val raw = JSONObject("""{"package_name":["example.chat"],"user_id":[10123],"action":"direct","tcp_fast_open":true,"connect_timeout":"5s"}""")
        val fixed = AppRouteIdentity.transform(raw)
        assertTrue(fixed.getBoolean("tcp_fast_open")); assertEquals("5s", fixed.getString("connect_timeout"))
        assertFalse(fixed.getJSONArray("rules").getJSONObject(0).has("tcp_fast_open"))
        val unrelated = SingBoxOptions.Rule_DefaultOptions().apply { domain_suffix = listOf("example.invalid"); outbound = "fixture-node" }
        assertSame(unrelated, AppRouteIdentity.route(unrelated))
    }

    @Test fun dnsKeepsFakeIpScopeAndServer() {
        val rule = SingBoxOptions.DNSRule_DefaultOptions().apply {
            package_name = listOf("example.chat"); user_id = listOf(10123)
            server = "dns-fake"; inbound = listOf("tun-in"); query_type = listOf("A", "AAAA")
        }
        val json = JSONObject(AppRouteIdentity.dns(rule).asMap())
        assertEquals("dns-fake", json.getString("server"))
        for (i in 0..1) {
            val child = json.getJSONArray("rules").getJSONObject(i)
            assertEquals("tun-in", child.getJSONArray("inbound").getString(0))
            assertEquals(2, child.getJSONArray("query_type").length())
        }
    }

    @Test fun nestedInversionAndUnresolvedPackageStayIntact() {
        val raw = JSONObject("""{"type":"logical","mode":"or","invert":true,"outbound":"fixture-node","rules":[{"package_name":["example.chat"],"user_id":[10123],"domain_suffix":["example.invalid"]},{"package_name":["example.missing"]}]}""")
        val fixed = AppRouteIdentity.transform(raw)
        assertTrue(fixed.getBoolean("invert")); assertEquals("fixture-node", fixed.getString("outbound"))
        assertEquals("logical", fixed.getJSONArray("rules").getJSONObject(0).getString("type"))
        assertEquals(raw.getJSONArray("rules").getJSONObject(1).toString(), fixed.getJSONArray("rules").getJSONObject(1).toString())
    }

    @Test fun uidSnapshotHandlesSharedAndSecondaryUsersWithoutChangingOldSnapshot() {
        val old = AppUidPackages.snapshot(listOf("example.one" to 10123, "example.two" to 10123))
        assertEquals(setOf("example.one", "example.two"), AppUidPackages.names(old, 10123))
        assertEquals(setOf("example.one", "example.two"), AppUidPackages.names(old, 1010123))
        val new = AppUidPackages.snapshot(listOf("example.new" to 10123))
        assertEquals(setOf("example.new"), AppUidPackages.names(new, 10123))
        assertEquals(2, AppUidPackages.names(old, 10123).size)
        assertTrue(AppUidPackages.names(old, -1).isEmpty()); assertTrue(AppUidPackages.names(old, 10999).isEmpty())
    }
}
