// @author 雾晚
package io.nekohasekai.sagernet.utils

/** Expiring, generation-bound exit data; retired requests cannot repopulate it. @author 雾晚 */
internal class LandingIpCache(private val now: () -> Long = System::nanoTime) {
    data class Ticket(val generation: Long, val profile: Long)
    private var generation = 0L
    private var entry: LandingIpInfo? = null
    private var profile = -1L
    private var savedAt = 0L
    @Synchronized fun clear() { generation++; entry = null; profile = -1L }
    @Synchronized fun ticket(profile: Long): Ticket {
        // A newer query retires older tickets even when the selected node switches back.
        generation++
        return Ticket(generation, profile)
    }
    @Synchronized fun get(profile: Long? = null): LandingIpInfo? {
        val age = now() - savedAt
        return entry?.takeIf { age in 0 until 60_000_000_000L && (profile == null || profile == this.profile) }
    }
    @Synchronized fun put(ticket: Ticket, info: LandingIpInfo): Boolean {
        if (ticket.generation != generation) return false
        entry = info; profile = ticket.profile; savedAt = now()
        return true
    }
    @Synchronized fun profileId() = if (get() != null) profile else -1L
    @Synchronized fun duration(duration: Long) { entry = get()?.copy(durationMs = duration) }
}
