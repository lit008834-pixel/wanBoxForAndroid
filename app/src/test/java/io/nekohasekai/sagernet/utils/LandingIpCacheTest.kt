// @author 雾晚
package io.nekohasekai.sagernet.utils

import org.junit.Assert.*
import org.junit.Test

/** Expiration and retired foreground request regressions. @author 雾晚 */
class LandingIpCacheTest {
    private fun info() = LandingIpInfo("192.0.2.1", "fixture", "PH", "", "", "", "", "", "", 1)
    @Test fun longBackgroundAndDifferentProfileNeverReuseOldExit() {
        var now = 0L
        val cache = LandingIpCache { now }
        assertTrue(cache.put(cache.ticket(1), info()))
        assertNotNull(cache.get(1)); assertNull(cache.get(2))
        now = 60_000_000_000L
        assertNull(cache.get(1)); assertEquals(-1L, cache.profileId())
    }
    @Test fun retiredRequestCannotOverwriteNewExitAfterDisconnectOrNodeChange() {
        val cache = LandingIpCache { 0 }
        val old = cache.ticket(1)
        cache.clear()
        val current = cache.ticket(2)
        assertTrue(cache.put(current, info().copy(ip = "198.51.100.1")))
        assertFalse(cache.put(old, info()))
        assertEquals("198.51.100.1", cache.get(2)!!.ip)
    }
}
