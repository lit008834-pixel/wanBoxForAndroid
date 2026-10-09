// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity

/** Explicit matches precede broad rule-set fallbacks; never rewrite Room order or custom JSON. @author 雾晚 */
internal object RouteRulePriority {
    fun isFallback(rule: RuleEntity): Boolean = rule.ruleset.isNotBlank() &&
        rule.config.isBlank() && rule.domains.isBlank() && rule.ip.isBlank() &&
        rule.port.isBlank() && rule.sourcePort.isBlank() && rule.network.isBlank() &&
        rule.source.isBlank() && rule.protocol.isBlank() && rule.packages.isEmpty()

    fun order(rules: List<RuleEntity>): List<RuleEntity> {
        val (fallback, explicit) = rules.partition(::isFallback)
        return explicit + fallback
    }
}
