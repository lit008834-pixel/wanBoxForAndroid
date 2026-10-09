// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg.proto

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory TTL cache for URL latency results. Batch re-tests within the TTL
 * reuse the cached ping instead of re-probing, saving time and battery.
 * Single-node manual tests always probe fresh (and refresh the cache).
 */
object UrlTestCache {
    private const val TTL_MS = 5 * 60_000L
    private val testedAt = ConcurrentHashMap<Long, Long>()
    private val pings = ConcurrentHashMap<Long, Int>()

    fun get(profileId: Long): Int? {
        val at = testedAt[profileId] ?: return null
        if (System.currentTimeMillis() - at > TTL_MS) {
            testedAt.remove(profileId)
            pings.remove(profileId)
            return null
        }
        return pings[profileId]
    }

    fun put(profileId: Long, ping: Int) {
        if (ping > 0) {
            testedAt[profileId] = System.currentTimeMillis()
            pings[profileId] = ping
        }
    }

    fun invalidate(profileId: Long) {
        testedAt.remove(profileId)
        pings.remove(profileId)
    }
}
