// @author 雾晚
package io.nekohasekai.sagernet.widget

import org.junit.Assert.*
import org.junit.Test

class LatencyProbeStateTest {
    @Test fun bothModesPublishExactCurrentResultsAndClearFailureOrZero() {
        for (mode in listOf("vpn", "root")) {
            val state = LatencyProbeState()
            var ticket = state.begin(mode, 1000)!!
            assertTrue(state.complete(ticket, mode, 47, 1001))
            assertEquals(47, state.latency)
            state.release(ticket)
            ticket = state.begin(mode, 1500)!!
            assertEquals(-1, state.latency)
            assertTrue(state.complete(ticket, mode, 0, 1501))
            assertEquals(-1, state.latency)
            state.release(ticket)
            ticket = state.begin(mode, 1502)!!
            assertTrue(state.complete(ticket, mode, -1, 1503))
            assertEquals(-1, state.latency)
        }
    }

    @Test fun reconnectOrModeSwitchRejectsOldResultAndKeepsSingleFlight() {
        val state = LatencyProbeState()
        val old = state.begin("vpn", 1000)!!
        assertNull(state.begin("vpn", 1001))
        state.invalidate()
        assertNull(state.begin("root", 1002))
        assertFalse(state.complete(old, "root", 2222, 1003))
        assertEquals(-1, state.latency)
        state.release(old)
        val fresh = state.begin("root", 1004)!!
        assertTrue(state.complete(fresh, "root", 55, 1005))
        state.release(old) // a late cancellation must not release a newer request
        assertNull(state.begin("root", 2000))
        state.release(fresh)
        assertEquals(55, state.latency)
    }

    @Test fun disconnectAndSameModeReconnectDiscardOldGeneration() {
        val state = LatencyProbeState()
        val old = state.begin("vpn", 1000)!!
        state.invalidate()
        assertFalse(state.complete(old, null, 88, 1001))
        state.synchronize("vpn")
        assertFalse(state.complete(old, "vpn", 88, 1002))
        assertEquals(-1, state.latency)
        state.release(old)
        assertNotNull(state.begin("vpn", 1003))
    }

    @Test fun changedBinderProfileUrlOrTimeoutClearsCachedResult() {
        val state = LatencyProbeState()
        val old = state.begin("service/profile/url/timeout", 1000)!!
        assertTrue(state.complete(old, old.key, 51, 1001)); state.release(old)
        assertNull(state.begin(old.key, 1200)) // preserve upstream 400 ms debounce
        assertTrue(state.synchronize("different-session"))
        assertEquals(-1, state.latency)
        assertNotNull(state.begin("different-session", 1201))
    }
    @Test fun redrawPreservesFailureUntilNewRequestOrSessionChange() {
        val state = LatencyProbeState()
        val ticket = state.begin("vpn", 1000)!!
        assertTrue(state.testing)
        assertTrue(state.complete(ticket, "vpn", 0, 1001))
        state.release(ticket)
        assertTrue(state.failed)
        assertFalse(state.testing)
        assertFalse(state.synchronize("vpn"))
        assertTrue(state.failed)
        val retry = state.begin("vpn", 1002)!!
        assertFalse(state.failed)
        assertTrue(state.testing)
        state.invalidate()
        assertFalse(state.failed)
        assertFalse(state.testing)
        state.release(retry)
    }

}
