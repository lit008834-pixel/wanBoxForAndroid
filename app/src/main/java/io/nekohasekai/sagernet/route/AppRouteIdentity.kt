// @author 雾晚
package io.nekohasekai.sagernet.route

import moe.matsuri.nb4a.SingBoxOptions
import org.json.JSONArray
import org.json.JSONObject

/** Package and socket UID are alternative proofs of the same Android identity. @author 雾晚 */
object AppRouteIdentity {
    // Action options belong to the outer logical rule (pinned sing-box schema).
    private val actions = setOf(
        "action", "answer", "client_subnet", "disable_cache", "disable_optimistic_cache",
        "extra", "fallback_delay", "method", "network_strategy", "no_drop",
        "ns", "outbound", "override_address", "override_port", "race",
        "rcode", "remove_client_subnet", "rewrite_ttl", "server", "sniffer",
        "speculative", "strategy", "tag", "timeout", "tls_fragment",
        "tls_fragment_fallback_delay", "tls_record_fragment", "tls_spoof", "tls_spoof_method", "udp_connect",
        "udp_disable_domain_unmapping", "udp_timeout"
    )

    fun route(rule: SingBoxOptions.Rule): SingBoxOptions.Rule {
        val original = JSONObject(rule.asMap())
        if (!needsAlternatives(original)) return rule
        val fixed = transform(original)
        return SingBoxOptions.Rule().apply { _hack_custom_config = fixed.toString() }
    }

    fun dns(rule: SingBoxOptions.DNSRule): SingBoxOptions.DNSRule {
        val original = JSONObject(rule.asMap())
        if (!needsAlternatives(original)) return rule
        val fixed = transform(original)
        return SingBoxOptions.DNSRule().apply { _hack_custom_config = fixed.toString() }
    }

    private fun needsAlternatives(json: JSONObject): Boolean {
        if (json.optString("type") == "logical") {
            val children = json.getJSONArray("rules")
            return (0 until children.length()).any { needsAlternatives(children.getJSONObject(it)) }
        }
        return (json.optJSONArray("package_name")?.length() ?: 0) > 0 &&
            (json.optJSONArray("user_id")?.length() ?: 0) > 0
    }

    fun transform(original: JSONObject): JSONObject {
        val result = JSONObject(original.toString())
        if (result.optString("type") == "logical") {
            val children = result.getJSONArray("rules")
            for (i in 0 until children.length()) children.put(i, transform(children.getJSONObject(i)))
            return result
        }
        if ((result.optJSONArray("package_name")?.length() ?: 0) == 0 ||
            (result.optJSONArray("user_id")?.length() ?: 0) == 0) return result

        // Direct uses dialer options. network_type is an existing match predicate and stays scoped.
        val actionFields = if (result.optString("action") == "direct") actions + setOf(
            "bind_interface", "inet4_bind_address", "inet6_bind_address", "bind_address_no_port",
            "protect_path", "routing_mark", "reuse_addr", "netns", "connect_timeout",
            "tcp_fast_open", "tcp_multi_path", "disable_tcp_keep_alive", "tcp_keep_alive",
            "tcp_keep_alive_interval", "udp_fragment", "domain_resolver", "fallback_network_type",
            "domain_strategy"
        ) else actions
        val byPackage = JSONObject(result.toString())
        val byUid = JSONObject(result.toString())
        byPackage.remove("user_id")
        byUid.remove("package_name")
        // Other predicates stay in BOTH branches. Inversion applies to the entire OR once.
        (actionFields + "invert" + "type").forEach { byPackage.remove(it); byUid.remove(it) }
        return JSONObject().apply {
            put("type", "logical"); put("mode", "or")
            put("rules", JSONArray().put(byPackage).put(byUid))
            (actionFields + "invert").forEach { if (result.has(it)) put(it, result.get(it)) }
        }
    }
}
