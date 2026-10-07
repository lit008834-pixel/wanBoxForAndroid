// @author 雾晚
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.proto.NodeTestRunner
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Tests real suspended probe concurrency and cancellation. @author 雾晚 */
class NodeTestRunnerTest {
    @Test fun suspendedAttemptsNeverExceedFourAndReceiveRequestedUrlTimeout()=runBlocking {
        val active=AtomicInteger();val peak=AtomicInteger()
        coroutineScope { (1..20).map { async {
            NodeTestRunner.measure { url,timeout ->
                assertEquals("http://connectivitycheck.gstatic.com/generate_204",url);assertEquals(5000,timeout)
                val n=active.incrementAndGet();peak.updateAndGet { maxOf(it,n) }
                try { delay(20); 42 } finally { active.decrementAndGet() }
            }
        } }.awaitAll().forEach { assertEquals(42,it) } }
        assertTrue(peak.get() in 1..4);assertEquals(0,active.get())
    }
    @Test fun transientFailureRetriesOnceAndPermanentOrZeroResultsDoNotBecomeSuccess()=runBlocking {
        var calls=0
        val start=System.nanoTime()
        assertEquals(73,NodeTestRunner.measure { _,_->if(++calls==1)throw IOException("fixture transient");73 })
        assertEquals(2,calls);assertTrue((System.nanoTime()-start)/1000000>=290)
        calls=0
        try { NodeTestRunner.measure { _,_->calls++;0 };fail() } catch(_:IOException) {}
        assertEquals(2,calls)
        calls=0
        try { NodeTestRunner.measure(retryable={false}) { _,_->calls++;throw IllegalArgumentException("fixture invalid") };fail() }
        catch(_:IllegalArgumentException) {}
        assertEquals(1,calls)
    }
    @Test fun cancellationStopsRetryAndReleasesPermit()=runBlocking {
        val entered=CompletableDeferred<Unit>();var calls=0
        val job=launch { NodeTestRunner.measure { _,_->calls++;entered.complete(Unit);awaitCancellation() } }
        entered.await();job.cancelAndJoin();assertEquals(1,calls)
        assertEquals(11,NodeTestRunner.measure { _,_->11 })
    }
    @Test fun childPluginFailureCanRetryWhileCallerRemainsActive()=runBlocking {
        var calls=0
        assertEquals(18,NodeTestRunner.measure { _,_->
            if(++calls==1)throw CancellationException("fixture plugin").apply { initCause(IOException("fixture restart")) }
            18
        })
        assertEquals(2,calls)
    }
}
