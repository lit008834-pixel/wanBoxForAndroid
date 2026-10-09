// @author 雾晚
package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Exercised coroutine ownership, not a Root device test. @author 雾晚 */
class RootModuleObservationTest {
    @Test fun repeatedOwnersSampleImmediatelyAndNeverContinueAfterUnbind() = runBlocking {
        var samples = 0
        var listeners = 0
        repeat(5) {
            val ready = CompletableDeferred<Unit>()
            val registered = CompletableDeferred<Unit>()
            val job = launch {
                RootModuleObservation.run(network = {
                    listeners++; registered.complete(Unit)
                    try { awaitCancellation() } finally { listeners-- }
                }, sample = { samples++; ready.complete(Unit) }, intervalMs = 10000)
            }
            withTimeout(2000) { ready.await(); registered.await() }
            job.cancelAndJoin()
            assertEquals(0, listeners)
        }
        delay(30)
        assertEquals(5, samples)
    }
    @Test fun stalledNetworkDoesNotBlockFirstStatusAndCancellationReleasesBoth() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val sampled = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        var samples = 0
        val job = launch {
            RootModuleObservation.run(network = {
                try { started.complete(Unit); awaitCancellation() }
                finally { released.complete(Unit) }
            }, sample = { samples++; sampled.complete(Unit) }, intervalMs = 10)
        }
        withTimeout(2000) { started.await(); sampled.await() }
        withTimeout(2000) { job.cancelAndJoin(); released.await() }
        val count = samples
        delay(30)
        assertTrue(count > 0); assertEquals(count, samples)
    }
    @Test fun networkRegistrationFailureDoesNotStopModuleStatus() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val failed = CompletableDeferred<Unit>()
        val job = launch {
            RootModuleObservation.run(network = { failed.complete(Unit); error("fixture registration failure") },
                sample = { if (failed.isCompleted) ready.complete(Unit) }, intervalMs = 10)
        }
        withTimeout(2000) { ready.await() }
        assertTrue(job.isActive)
        job.cancelAndJoin()
    }
}
