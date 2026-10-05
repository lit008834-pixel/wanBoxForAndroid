// @author 雾晚
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.route.InputMethodDirectPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Tests the actual serialized identity rules, using fictional packages. @author 雾晚 */
class InputMethodDirectPolicyTest {
    @Test fun componentAndSecondaryUserUidArePreserved() {
        val identity = InputMethodDirectPolicy.identity("fixture.keyboard/.ImeService", 210123, 10101)!!
        assertEquals("fixture.keyboard", identity.packageName)
        assertEquals(210123, identity.uid)
        assertEquals("fixture.keyboard", InputMethodDirectPolicy.packageName("fixture.keyboard/fixture.keyboard.Ime\$Service"))
    }

    @Test fun unavailableMalformedPlatformIsolatedAndOwnIdentitiesFailClosed() {
        for (component in listOf(null, "", "fixture.keyboard", "fixture.keyboard/", "bad package/.Ime", "fixture.keyboard/.Ime/extra")) {
            assertNull(InputMethodDirectPolicy.identity(component, 10123, 10101))
        }
        for (uid in listOf(null, -1, 0, 1000, 201000, 99000, 299000, 10101)) {
            assertNull(InputMethodDirectPolicy.identity("fixture.keyboard/.Ime", uid, 10101))
        }
    }

    @Test fun routeAndDnsMatchPackageOrFullUidWithoutBroadSystemExemption() {
        val identity = InputMethodDirectPolicy.identity("fixture.keyboard/.Ime", 210123, 10101)!!
        val route = JSONObject(InputMethodDirectPolicy.route(identity).asMap())
        val dns = JSONObject(InputMethodDirectPolicy.dns(identity).asMap())
        for (rule in listOf(route, dns)) {
            assertEquals("logical", rule.getString("type"))
            assertEquals("or", rule.getString("mode"))
            val branches = rule.getJSONArray("rules")
            assertEquals(2, branches.length())
            assertEquals("fixture.keyboard", branches.getJSONObject(0).getJSONArray("package_name").getString(0))
            assertEquals(210123, branches.getJSONObject(1).getJSONArray("user_id").getInt(0))
            assertEquals(1, branches.getJSONObject(1).getJSONArray("user_id").length())
            assertFalse(rule.has("inbound")); assertFalse(rule.has("ip_version"))
        }
        assertEquals("direct", route.getString("outbound"))
        assertEquals("dns-direct", dns.getString("server"))
        assertTrue(dns.getBoolean("disable_cache"))
    }
}
