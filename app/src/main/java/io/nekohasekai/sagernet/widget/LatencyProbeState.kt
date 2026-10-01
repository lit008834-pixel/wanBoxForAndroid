// @author 雾晚
package io.nekohasekai.sagernet.widget

/** Main-thread state; invalidation does not release an unfinished blocking Binder request. */
internal class LatencyProbeState {
    data class Ticket(val generation: Long, val key: Any)
    private var generation = 0L
    private var key: Any? = null
    private var active: Ticket? = null
    var latency = -1
        private set
    var failed = false
        private set
    val testing get() = active?.let { it.generation == generation && it.key == key } == true
    private var measuredAt = 0L

    fun synchronize(current: Any?): Boolean {
        if (key == current) return false
        key = current
        generation++
        latency = -1
        failed = false
        measuredAt = 0L
        return true
    }

    fun invalidate() { generation++; key = null; latency = -1; failed = false; measuredAt = 0L }

    fun begin(current: Any, now: Long): Ticket? {
        synchronize(current)
        if (active != null || (latency > 0 && now - measuredAt < 400L)) return null
        return Ticket(generation, current).also { active = it; latency = -1; failed = false }
    }

    fun complete(ticket: Ticket, current: Any?, result: Int, now: Long): Boolean {
        synchronize(current)
        if (active !== ticket || generation != ticket.generation || key != ticket.key) return false
        latency = if (result > 0) result else -1
        failed = result <= 0
        measuredAt = if (result > 0) now else 0L
        return true
    }

    fun release(ticket: Ticket) { if (active === ticket) active = null }
}
