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
}
