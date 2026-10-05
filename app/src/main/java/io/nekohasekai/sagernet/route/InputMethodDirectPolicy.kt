// @author 雾晚
package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.fmt.TAG_DIRECT
import moe.matsuri.nb4a.SingBoxOptions

/** Opt-in compatibility for the active keyboard; never exempts every system application. @author 雾晚 */
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

    fun route(identity: Identity): SingBoxOptions.Rule = AppRouteIdentity.route(
        SingBoxOptions.Rule_DefaultOptions().apply {
            package_name = listOf(identity.packageName)
            user_id = listOf(identity.uid)
            outbound = TAG_DIRECT
        }
    )

    fun dns(identity: Identity): SingBoxOptions.DNSRule = AppRouteIdentity.dns(
        SingBoxOptions.DNSRule_DefaultOptions().apply {
            package_name = listOf(identity.packageName)
            user_id = listOf(identity.uid)
            server = "dns-direct"
            disable_cache = true
        }
    )
}
