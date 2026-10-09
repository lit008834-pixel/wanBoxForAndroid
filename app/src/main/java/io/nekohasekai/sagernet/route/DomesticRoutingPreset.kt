// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase

/** Reuse geographic data already supported by wanBox; preserve unrelated/user rules. @author 雾晚 */
internal object DomesticRoutingPreset {
    fun rules(domainName: String, ipName: String) = listOf(
        RuleEntity(name = domainName, domains = "geosite:cn", outbound = -1, enabled = true),
        RuleEntity(name = ipName, ip = "geoip:cn", outbound = -1, enabled = true),
    )

    fun equivalent(existing: RuleEntity, candidate: RuleEntity): Boolean =
        RouteRulePriority.isFallback(existing) && existing.config.isBlank() &&
        existing.ruleset.isBlank() && existing.outbound == candidate.outbound &&
        existing.domains.trim().replace("geosite-cn", "geosite:cn") == candidate.domains &&
        existing.ip.trim().replace("geoip-cn", "geoip:cn") == candidate.ip

    // Two entries are committed atomically before the caller requests a snapshot.
    // An existing equivalent rule retains its ID, name, outbound and position.
    fun apply(domainName: String, ipName: String) {
        val dao = SagerDatabase.rulesDao
        SagerDatabase.instance.runInTransaction {
            val existing = dao.allRules()
            for (candidate in rules(domainName, ipName)) {
                val previous = existing.firstOrNull { equivalent(it, candidate) }
                if (previous != null) {
                    if (!previous.enabled) dao.updateRule(previous.copy(enabled = true))
                } else {
                    candidate.userOrder = dao.nextOrder() ?: 1
                    dao.createRule(candidate)
                }
            }
        }
    }
}
