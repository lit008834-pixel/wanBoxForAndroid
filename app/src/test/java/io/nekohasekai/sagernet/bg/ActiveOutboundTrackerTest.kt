// @author 雾晚
package io.nekohasekai.sagernet.bg

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** @author 雾晚 */
class ActiveOutboundTrackerTest {
    @Test fun membersAreObservedNeverGuessedAndSessionsAreIsolated() {
        val old = ActiveOutboundTracker(mapOf("p-1" to "Node A", "p-2" to "Node B"))
        assertEquals("Node A", old.resolve("p-1"))
        assertEquals("Node B", old.resolve("p-2"))
        assertNull(old.resolve("missing"))
        assertNull(old.resolve(""))
        assertNull(ActiveOutboundTracker(emptyMap()).resolve("p-2"))
        assertEquals("New node", ActiveOutboundTracker(mapOf("p-1" to "New node")).resolve("p-1"))
    }

    @Test fun dirtyCacheIncludesPreferencesAndFailedPublishesCanRetry() {
        val cache = NotificationContentCache()
        val original = NotificationContentCache.Content("Group", "Node · ↑0 ↓0", "Group\nNode\n↑0 ↓0")
        assertTrue(cache.changed(original)) // A failed notify must not commit.
        assertTrue(cache.changed(original))
        cache.committed(original)
        assertFalse(cache.changed(original))
        val direct = original.copy(expanded = original.expanded + "\nDirect ↑1 ↓2")
        assertTrue(cache.changed(direct))
        cache.committed(direct)
        assertTrue(cache.changed(original)) // Direct display turned off: clear the old style.
        assertTrue(cache.changed(original.copy(title = "Node"))) // Group display turned off.
        cache.clear() // Stop/wake/recreate.
        assertTrue(cache.changed(direct))
    }

    @Test fun serializedDuplicateUpdatesPublishOnce() {
        val cache = NotificationContentCache()
        val content = NotificationContentCache.Content("Node", "↑0 ↓0", "Node\n↑0 ↓0")
        var publishes = 0
        val pool = Executors.newFixedThreadPool(4)
        repeat(100) { pool.submit { synchronized(cache) {
            if (cache.changed(content)) { publishes++; cache.committed(content) }
        } } }
        pool.shutdown()
        assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS))
        assertEquals(1, publishes)
    }
}
