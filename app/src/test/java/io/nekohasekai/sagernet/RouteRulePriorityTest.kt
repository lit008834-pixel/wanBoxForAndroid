// @author 雾晚
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.route.RouteRulePriority
import org.junit.Assert.*
import org.junit.Test

/** Matching priority is independent from a rule's outbound identity. @author 雾晚 */
class RouteRulePriorityTest {
    @Test fun explicitRulesPrecedeBroadSetsAndKeepOrderWithoutMutatingDatabaseValues() {
        val remote = RuleEntity(id = 1, userOrder = 0, ruleset = "rssite:https://example.invalid/list.srs", outbound = -1)
        val domain = RuleEntity(id = 2, userOrder = 1, domains = "domain:example.invalid", outbound = 10)
        val application = RuleEntity(id = 3, userOrder = 2, packages = setOf("invalid.fixture.app"), outbound = 11)
        val ip = RuleEntity(id = 4, userOrder = 3, ip = "192.0.2.0/24", outbound = -2)
        val otherSet = RuleEntity(id = 5, userOrder = 4, ruleset = "rsip:https://example.invalid/ip.srs")
        val input = listOf(remote, domain, application, ip, otherSet)
        val before = input.map { it.copy() }
        assertEquals(listOf(2L, 3L, 4L, 1L, 5L), RouteRulePriority.order(input).map { it.id })
        assertEquals(before, input)
    }
    @Test fun ScopedSetsAndCustomJsonCannotBeSilentlyDemoted() {
        val broad = RuleEntity(ruleset = "rssite:https://example.invalid/list.srs")
        for (rule in listOf(broad.copy(domains = "domain:example.invalid"), broad.copy(ip = "192.0.2.1"),
            broad.copy(port = "443"), broad.copy(sourcePort = "1000"), broad.copy(source = "192.0.2.0/24"),
            broad.copy(network = "udp"), broad.copy(protocol = "dns"), broad.copy(packages = setOf("fixture.app")),
            broad.copy(config = "{\"invert\":true}"))) assertFalse(RouteRulePriority.isFallback(rule))
        assertFalse(RouteRulePriority.isFallback(RuleEntity(config = "{\"rule_set\":[\"custom\"]}")))
        assertEquals(emptyList<RuleEntity>(), RouteRulePriority.order(emptyList()))
    }
    @Test fun legacyGeoReferencesCannotShadowAppOrLiteralRules() {
        val domestic = RuleEntity(id = 1, domains = "geosite:cn", userOrder = 1)
        val ips = RuleEntity(id = 2, ip = "geoip-cn", userOrder = 2)
        val app = RuleEntity(id = 3, packages = setOf("fixture.app"), userOrder = 3)
        val domain = RuleEntity(id = 4, domains = "domain:fixture.invalid", userOrder = 4)
        val literalIp = RuleEntity(id = 5, ip = "192.0.2.0/24", userOrder = 5)
        assertEquals(listOf(3L, 4L, 5L, 1L, 2L),
            RouteRulePriority.order(listOf(domestic, ips, app, domain, literalIp)).map { it.id })
        assertTrue(RouteRulePriority.isFallback(domestic.copy(domains = "geosite:cn, geosite-google")))
        assertFalse(RouteRulePriority.isFallback(domestic.copy(domains = "geosite:cn\ndomain:fixture.invalid")))
        assertFalse(RouteRulePriority.isFallback(ips.copy(ip = "geoip:cn\n192.0.2.1")))
        assertFalse(RouteRulePriority.isFallback(domestic.copy(packages = setOf("fixture.app"))))
        assertFalse(RouteRulePriority.isFallback(ips.copy(config = "{\"invert\":true}")))
        assertTrue(RouteRulePriority.needsIpResolution(ips))
        assertFalse(RouteRulePriority.needsIpResolution(domestic))
        assertFalse(RouteRulePriority.needsIpResolution(literalIp))
        assertFalse(RouteRulePriority.needsIpResolution(ips.copy(ip = "geoip:private")))
    }

}
