// @author 雾晚
package io.nekohasekai.sagernet.bg

/** Pure scheduling policy; keeps each subscription's own due time. @author 雾晚 */
object SubscriptionSchedule {
    data class Entry(val intervalMinutes: Int, val lastUpdatedSeconds: Int)
    data class Plan(val intervalMinutes: Long, val initialDelaySeconds: Long)
    fun plan(entries: List<Entry>, nowSeconds: Long): Plan? {
        if (entries.isEmpty()) return null
        fun Entry.interval() = intervalMinutes.toLong().coerceAtLeast(15)
        return Plan(entries.minOf { it.interval() }, entries.minOf {
            (it.lastUpdatedSeconds.toLong() + it.interval() * 60 - nowSeconds).coerceAtLeast(0)
        })
    }
}
