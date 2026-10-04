// @author 雾晚
package io.nekohasekai.sagernet.bg

import org.json.JSONObject

/** Bounded, credential-free samples from our root core's existing watchdog pipe.
 * @author 雾晚
 */
data class RootNotificationSample(val tag: String, val tx: Long, val rx: Long,
    val directTx: Long, val directRx: Long) {
    companion object {
        const val PREFIX = "WANBOX_STATS:"
        fun parse(line: String): RootNotificationSample? {
            if (!line.startsWith(PREFIX) || line.length > 2048) return null
            return try {
                val json = JSONObject(line.removePrefix(PREFIX))
                val tag = json.getString("tag")
                if (tag.length > 512 || tag.any { it.isISOControl() }) return null
                fun rate(key: String): Long = (json.get(key) as? Number)?.toString()?.toLongOrNull()?.also { require(it >= 0) }
                    ?: throw IllegalArgumentException("Invalid rate")
                RootNotificationSample(tag, rate("tx"), rate("rx"), rate("directTx"), rate("directRx"))
            } catch (_: IllegalArgumentException) { null }
              catch (_: org.json.JSONException) { null }
        }
    }
}
