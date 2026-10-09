// @author 雾晚
package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Real coroutine queue, cancellation and error recovery; no Root process is mocked as connected. @author 雾晚 */
class CoalescedReloadTest {
    @Test fun burstReadsLatestDataOnceAndStaysIdle() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val applied = CompletableDeferred<Int>()
        var value = 0
        var count = 0
        try {
            val reload = CoalescedReload(owner, 80, apply = { count++; applied.complete(value) }, onError = { throw it })
            repeat(100) { value = it; assertTrue(reload.request()) }
            assertEquals(99, withTimeout(2000) { applied.await() })
            delay(180)
            assertEquals(1, count)
        } finally { owner.cancel(); owner.coroutineContext[Job]!!.join() }
    }

    @Test fun editDuringApplyDoesNotCancelTransactionOrLoseLastEdit() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        var active = 0
        var maxActive = 0
        var count = 0
        var completed = false
        try {
            val reload = CoalescedReload(owner, 30, apply = {
                active++; maxActive = maxOf(maxActive, active)
                try {
                    count++
                    if (count == 1) { first.complete(Unit); release.await(); completed = true }
                    else second.complete(Unit)
                } finally { active-- }
            }, onError = { throw it })
            reload.request()
            withTimeout(2000) { first.await() }
            repeat(200) { reload.request() }
            delay(70)
            assertFalse(completed); assertEquals(1, count)
            release.complete(Unit)
            withTimeout(2000) { second.await() }
            delay(70)
            assertTrue(completed); assertEquals(2, count); assertEquals(1, maxActive)
        } finally { owner.cancel(); owner.coroutineContext[Job]!!.join() }
    }

    @Test fun failureIsReportedAndNextEditStillApplies() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val failed = CompletableDeferred<Exception>()
        val recovered = CompletableDeferred<Unit>()
        var count = 0
        try {
            val reload = CoalescedReload(owner, 20, apply = {
                if (++count == 1) throw java.io.IOException("fixture_failure")
                recovered.complete(Unit)
            }, onError = { failed.complete(it) })
            reload.request()
            assertEquals("fixture_failure", withTimeout(2000) { failed.await() }.message)
            reload.request()
            withTimeout(2000) { recovered.await() }
            assertEquals(2, count)
        } finally { owner.cancel(); owner.coroutineContext[Job]!!.join() }
    }

    @Test fun ownerCancellationDiscardsPendingEditsWithoutApply() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var count = 0
        val reload = CoalescedReload(owner, 200, apply = { count++ }, onError = { throw it })
        reload.request()
        owner.cancel(); owner.coroutineContext[Job]!!.join()
        delay(250)
        assertEquals(0, count); assertFalse(reload.request())
    }
    @Test fun alreadyCancelledOwnerRejectsRequestsEvenIfWorkerNeverStarts() = runBlocking {
        val parent = SupervisorJob()
        parent.cancel(); parent.join()
        val queue = CoalescedReload(CoroutineScope(parent + Dispatchers.Default), 20,
            apply = { fail("Cancelled queue must not apply") }, onError = { throw it })
        parent.join()
        assertFalse(queue.request())
    }

    @Test fun manualSelectionWakesQuietWindowImmediatelyEvenAfterPassiveEdit() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val applied = CompletableDeferred<Unit>()
        try {
            val queue = CoalescedReload(owner, 10000, apply = { applied.complete(Unit) }, onError = { throw it })
            queue.request(); delay(30)
            queue.request(immediate = true); queue.request() // passive signal cannot erase urgency
            withTimeout(2000) { applied.await() }
        } finally { owner.cancel(); owner.coroutineContext[Job]!!.join() }
    }
    @Test fun selectionDuringApplyWaitsForTransactionButSkipsNextQuietWindow() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>(); var count = 0
        try {
            val queue = CoalescedReload(owner, 10000, apply = {
                if (++count == 1) { first.complete(Unit); release.await() } else second.complete(Unit)
            }, onError = { throw it })
            queue.request(immediate = true); withTimeout(2000) { first.await() }
            queue.request(immediate = true); assertEquals(1, count)
            release.complete(Unit); withTimeout(2000) { second.await() }; assertEquals(2, count)
        } finally { owner.cancel(); owner.coroutineContext[Job]!!.join() }
    }

}
