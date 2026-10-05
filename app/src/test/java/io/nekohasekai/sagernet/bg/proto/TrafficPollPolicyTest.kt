// @author 雾晚
package io.nekohasekai.sagernet.bg.proto

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Exercise the production suspension/cancellation and interval policy. @author 雾晚 */
class TrafficPollPolicyTest {
    @Test fun configuredForegroundAndExistingBackgroundIntervalsArePreserved() {
        for (priority in listOf(false, true)) {
            for (interval in listOf(500L, 1000L, 2000L)) assertEquals(interval,
                TrafficPollPolicy.intervalMs(true, true, true, priority, interval))
            assertEquals(if (priority) 10000L else 30000L,
                TrafficPollPolicy.intervalMs(false, false, true, priority, 1000))
            assertEquals(if (priority) 3000L else 6000L,
                TrafficPollPolicy.intervalMs(false, true, true, priority, 1000))
            assertEquals(if (priority) 5000L else 15000L,
                TrafficPollPolicy.intervalMs(false, true, false, priority, 1000))
        }
    }

    @Test fun unreadyCoreSuspendsWithoutBlockingAndCancellationReleasesJob() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val poll = async(start = CoroutineStart.UNDISPATCHED) {
            entered.complete(Unit)
            TrafficPollPolicy.ready(false, true, 1000)
        }
        entered.await()
        assertFalse(poll.isCompleted)
        withTimeout(1000) { poll.cancelAndJoin() }
        assertTrue(poll.isCancelled)
        assertTrue(TrafficPollPolicy.ready(true, false, 1000))
    }

    @Test fun backgroundUnreadyCoreIsAlsoCancellable() = runBlocking {
        val poll = async(start = CoroutineStart.UNDISPATCHED) { TrafficPollPolicy.ready(false, false, 1) }
        assertFalse(poll.isCompleted)
        withTimeout(1000) { poll.cancelAndJoin() }
        assertTrue(poll.isCancelled)
        assertEquals(1L, TrafficPollPolicy.intervalMs(true, true, false, false, -1))
    }
}
