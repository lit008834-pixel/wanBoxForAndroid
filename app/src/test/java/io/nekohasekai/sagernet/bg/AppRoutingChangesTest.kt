// @author 雾晚
package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.route.AppRoutingChanges
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Verify persisted edit dispatch and the real shared coroutine queue. @author 雾晚 */
class AppRoutingChangesTest {
    @Test fun onlyPersistedPolicyKeysRequestApply() {
        var count = 0
        for (key in listOf(Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL))
            AppRoutingChanges.onSaved(key) { count++ }
        assertEquals(3, count)
        for (key in listOf(Key.PROFILE_CURRENT, Key.APP_THEME, Key.PROFILE_GROUP, Key.CONNECTION_TEST_URL))
            AppRoutingChanges.onSaved(key) { count++ }
        assertEquals(3, count)
    }
    @Test fun policyBurstUsesLatestSavedDataOnceAndDoesNotApplyOnConstruction() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val applied = CompletableDeferred<Int>()
        val current = AtomicInteger(0)
        val count = AtomicInteger(0)
        try {
            val queue = CoalescedReload(owner, 40, apply = {
                count.incrementAndGet(); applied.complete(current.get())
            }, onError = { throw it })
            delay(80); assertEquals(0, count.get())
            repeat(100) { current.set(it); AppRoutingChanges.onSaved(Key.INDIVIDUAL) { queue.request() } }
            assertEquals(99, withTimeout(2000) { applied.await() })
            delay(100); assertEquals(1, count.get())
        } finally { owner.cancel(); owner.coroutineContext[Job]!!.join() }
    }
    @Test fun applicationOwnsOneMainProcessListenerInsteadOfActivityManualApply() {
        val application = File("src/main/java/io/nekohasekai/sagernet/SagerNet.kt").readText()
        val main = application.substringAfter("if (isMainProcess) {").substringBefore("if (BuildConfig.DEBUG)")
        assertTrue(main.contains("registerChangeListener(appRoutingListener)"))
        assertTrue(main.indexOf("migrateSubscriptionUserAgents()") < main.indexOf("registerChangeListener(appRoutingListener)"))
        assertTrue(application.contains("AppRoutingChanges.onSaved(key, ::reloadService)"))
        assertTrue(application.contains("startOrReload(startIfStopped = false)"))
        assertFalse(File("src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt").readText()
            .contains("Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL ->"))
    }
}
