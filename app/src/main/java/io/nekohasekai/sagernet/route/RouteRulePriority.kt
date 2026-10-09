// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity

/** Explicit matches precede broad rule-set fallbacks; never rewrite Room order or custom JSON. @author 雾晚 */
internal object RouteRulePriority {
    // Legacy geographic references are compiled into rule sets too. Treat them as
    // fallbacks only when no literal/scoped/custom predicate would lose priority.
    private fun references(value: String, prefix: String): Boolean =
        value.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            .all { (it.startsWith("$prefix:") || it.startsWith("$prefix-")) && it.length > prefix.length + 1 }

    fun isFallback(rule: RuleEntity): Boolean =
        (rule.ruleset.isNotBlank() || rule.domains.isNotBlank() || rule.ip.isNotBlank()) &&
        rule.config.isBlank() && references(rule.domains, "geosite") && references(rule.ip, "geoip") &&
        rule.port.isBlank() && rule.sourcePort.isBlank() && rule.network.isBlank() &&
        rule.source.isBlank() && rule.protocol.isBlank() && rule.packages.isEmpty()

    fun needsIpResolution(rule: RuleEntity): Boolean = isFallback(rule) &&
        rule.domains.isBlank() && rule.ruleset.isBlank() && rule.ip.isNotBlank() &&
        rule.ip.split(',', '\n').any { it.trim().isNotEmpty() && it.trim() !in setOf("geoip:private", "geoip-private") }

    fun order(rules: List<RuleEntity>): List<RuleEntity> {
        val (fallback, explicit) = rules.partition(::isFallback)
        return explicit + fallback
    }
}
