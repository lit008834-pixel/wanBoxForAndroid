// @author 雾晚
package io.nekohasekai.sagernet

import com.google.common.util.concurrent.SettableFuture
import io.nekohasekai.sagernet.bg.SubscriptionSchedule
import io.nekohasekai.sagernet.utils.awaitCancellable
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Background work deadlines and owned-future cancellation. @author 雾晚 */
class BackgroundScheduleTest {
    @Test fun dueTimeUsesEachSubscriptionIntervalAndDoesNotCapAtOneMinute() {
        val plan = SubscriptionSchedule.plan(listOf(
            SubscriptionSchedule.Entry(60, 1000), SubscriptionSchedule.Entry(120, 0)), 1100)!!
        assertEquals(60L, plan.intervalMinutes); assertEquals(3500L, plan.initialDelaySeconds)
        assertNull(SubscriptionSchedule.plan(emptyList(), 0))
    }
    @Test fun overdueAndInvalidIntervalsRespectWorkManagerMinimumWithoutOverflow() {
        val plan = SubscriptionSchedule.plan(listOf(SubscriptionSchedule.Entry(-1, 0)), 10000)!!
        assertEquals(15L, plan.intervalMinutes); assertEquals(0L, plan.initialDelaySeconds)
        assertTrue(SubscriptionSchedule.plan(listOf(SubscriptionSchedule.Entry(Int.MAX_VALUE, Int.MAX_VALUE)), 0)!!.initialDelaySeconds > Int.MAX_VALUE)
    }
    @Test fun futureCompletesAndCancellationCancelsOwnedRequest() = runBlocking {
        val done = SettableFuture.create<Int>(); done.set(42); assertEquals(42, done.awaitCancellable())
        val pending = SettableFuture.create<Int>()
        val task = launch(start = CoroutineStart.UNDISPATCHED) { pending.awaitCancellable() }
        task.cancelAndJoin(); assertTrue(pending.isCancelled)
    }
}
