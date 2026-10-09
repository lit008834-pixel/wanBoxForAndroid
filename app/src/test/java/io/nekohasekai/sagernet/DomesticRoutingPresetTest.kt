// @author 雾晚
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.DomesticRoutingPreset
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.makeSingBoxRule
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Optional presets cannot overwrite scoped/custom rules or invent country suffixes. @author 雾晚 */
class DomesticRoutingPresetTest {
    @Test fun usesExistingGeographicSetsAndDirectOutbound() {
        val rules = DomesticRoutingPreset.rules("domains", "IPs")
        assertEquals(2, rules.size)
        assertEquals("geosite:cn", rules[0].domains); assertEquals("geoip:cn", rules[1].ip)
        assertTrue(rules.all { it.enabled && it.outbound == -1L && it.config.isEmpty() })
    }
    @Test fun matchesOnlyEquivalentDirectRulesIncludingLegacyAlias() {
        val domain = DomesticRoutingPreset.rules("domains", "IPs")[0]
        assertTrue(DomesticRoutingPreset.equivalent(domain.copy(id = 9, name = "custom name", userOrder = 44, enabled = false), domain))
        assertTrue(DomesticRoutingPreset.equivalent(domain.copy(domains = "geosite-cn"), domain))
        for (different in listOf(domain.copy(domains = "geosite:cn\ndomain:fixture.invalid"),
            domain.copy(outbound = 12), domain.copy(packages = setOf("fixture.app")),
            domain.copy(config = "{\"invert\":true}"), domain.copy(ip = "geoip:cn")))
            assertFalse(DomesticRoutingPreset.equivalent(different, domain))
    }
    @Test fun serializerFixtureMatchesPinnedCoreDomainAndIpv4Ipv6Matcher() {
        val suffix = SingBoxOptions.Rule_DefaultOptions().apply {
            makeSingBoxRule(listOf("domain:cn.fixture.invalid"), false); outbound = "bypass"
        }
        val cidr = SingBoxOptions.Rule_DefaultOptions().apply {
            makeSingBoxRule(listOf("192.0.2.0/24", "2001:db8:1::/48"), true); outbound = "bypass"
        }
        val fixture = JSONArray(File("src/test/resources/domestic-rule-match.json").readText())
        assertTrue(fixture.getJSONObject(0).similar(JSONObject(suffix.asMap())))
        assertTrue(fixture.getJSONObject(1).similar(JSONObject(cidr.asMap())))
    }

}
