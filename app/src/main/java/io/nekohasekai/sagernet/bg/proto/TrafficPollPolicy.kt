// @author 雾晚
package io.nekohasekai.sagernet.bg.proto

import kotlinx.coroutines.delay

/** Sampling only: never throttles the core or changes service ownership. @author 雾晚 */
internal object TrafficPollPolicy {
    suspend fun ready(initialized: Boolean, foreground: Boolean, foregroundIntervalMs: Long): Boolean {
        if (initialized) return true
        // A missing box must suspend, including cancellation, instead of occupying a worker in a busy loop.
        delay(if (foreground) foregroundIntervalMs.coerceAtLeast(1000L) else 3000L)
        return false
    }

    fun intervalMs(foreground: Boolean, interactive: Boolean, notification: Boolean,
                   priority: Boolean, foregroundIntervalMs: Long): Long = when {
        foreground -> foregroundIntervalMs.coerceAtLeast(1L)
        !interactive -> if (priority) 10000L else 30000L
        notification -> if (priority) 3000L else 6000L
        else -> if (priority) 5000L else 15000L
    }
}
