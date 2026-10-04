// @author 雾晚
package io.nekohasekai.sagernet.group

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Uses fictional resolver results, suspended work and late blocking completion. @author 雾晚 */
class SubscriptionResolutionRunnerTest {
    @Test fun concurrentSubscriptionsShareFiveEntireLookupLifetimes() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val completed = AtomicInteger()
        withTimeout(5000) {
            coroutineScope {
                List(2) {
                    async {
                        SubscriptionResolutionRunner.run((1..15).toList(), resolve = {
                            val count = active.incrementAndGet()
                            peak.updateAndGet { old -> maxOf(old, count) }
                            try { delay(20); it } finally { active.decrementAndGet() }
                        }, onResolved = { item, value -> assertEquals(item, value) },
                            onFailure = { throw AssertionError(it) }, onFinished = { completed.incrementAndGet() })
                    }
                }.awaitAll()
            }
        }
        assertTrue("peak=${peak.get()}", peak.get() in 1..SubscriptionResolutionRunner.CONCURRENCY)
        assertEquals(0, active.get())
        assertEquals(30, completed.get())
    }

    @Test fun parentCancellationStopsSuspendedChildrenAndLateMutations() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val applied = AtomicInteger()
        val progressed = AtomicInteger()
        val failed = AtomicInteger()
        val job = launch {
            SubscriptionResolutionRunner.run(listOf(1), resolve = {
                entered.complete(Unit)
                release.await()
                it
            }, onResolved = { _, _ -> applied.incrementAndGet() },
                onFailure = { failed.incrementAndGet() }, onFinished = { progressed.incrementAndGet() })
        }
        try {
            withTimeout(5000) { entered.await(); job.cancelAndJoin() }
        } finally { release.complete(Unit) }
        delay(50)
        assertEquals(0, applied.get())
        assertEquals(0, progressed.get())
        assertEquals(0, failed.get())
        SubscriptionResolutionRunner.run(listOf(2), { it }, { _, _ -> applied.incrementAndGet() },
            { throw AssertionError(it) }, {})
        assertEquals(1, applied.get())
    }

    @Test fun blockingDnsFinishingAfterCancellationCannotRewriteTheNode() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val applied = AtomicInteger()
        val progressed = AtomicInteger()
        val job = launch {
            SubscriptionResolutionRunner.run(listOf(1), resolve = {
                entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
                it
            }, onResolved = { _, _ -> applied.incrementAndGet() },
                onFailure = { throw AssertionError(it) }, onFinished = { progressed.incrementAndGet() })
        }
        try {
            withTimeout(5000) { entered.await() }
            job.cancel()
        } finally { release.countDown() }
        withTimeout(5000) { job.join() }
        delay(50)
        assertEquals(0, applied.get())
        assertEquals(0, progressed.get())
    }

    @Test fun failedLookupIsReportedOnceAndDoesNotDiscardOtherResults() = runBlocking {
        val results = java.util.concurrent.ConcurrentHashMap<Int, Int>()
        val failures = AtomicInteger()
        val progressed = AtomicInteger()
        SubscriptionResolutionRunner.run(listOf(1, 2, 3), {
            if (it == 2) throw IOException("fixture DNS failure")
            it * 10
        }, { item, value -> results[item] = value }, { failures.incrementAndGet() }, { progressed.incrementAndGet() })
        assertEquals(mapOf(1 to 10, 3 to 30), results)
        assertEquals(1, failures.get())
        assertEquals(3, progressed.get())
    }
}
