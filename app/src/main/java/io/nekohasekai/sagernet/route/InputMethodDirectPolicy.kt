// @author 雾晚
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.database.RuleEntity

/** Builds an ordinary editable application route; never exempts all system applications. @author 雾晚 */
object InputMethodDirectPolicy {
    /** Captured once for a generated configuration, including the Android user ID. @author 雾晚 */
    data class Identity(val packageName: String, val uid: Int)

    private val packagePattern = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
    private val servicePattern = Regex("\\.?[A-Za-z_][A-Za-z0-9_.$]*")

    fun packageName(component: String?): String? {
        val parts = component?.trim()?.split('/') ?: return null
        if (parts.size != 2 || !packagePattern.matches(parts[0]) || !servicePattern.matches(parts[1])) return null
        return parts[0]
    }

    fun identity(component: String?, uid: Int?, ownUid: Int): Identity? {
        val name = packageName(component) ?: return null
        // System/shared platform UID or an isolated process must never exempt all system traffic.
        if (uid == null || uid == ownUid || uid < 0 || uid % 100000 !in 10000 until 99000) return null
        return Identity(name, uid)
    }

    fun rule(identity: Identity, name: String, enabled: Boolean) = RuleEntity(
        name = name, packages = setOf(identity.packageName), outbound = -1L, enabled = enabled
    )

    /** A disabled equivalent is intentional user state; migration must not enable it. @author 雾晚 */
    fun equivalent(rule: RuleEntity, identity: Identity) = rule.outbound == -1L &&
        rule.packages == setOf(identity.packageName) && rule.config.isBlank() &&
        listOf(rule.domains, rule.ip, rule.port, rule.sourcePort, rule.network,
            rule.source, rule.protocol, rule.ruleset).all { it.isBlank() }
}
