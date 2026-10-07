// @author 雾晚
package io.nekohasekai.sagernet.bg.proto

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

/**
 * Limits entire probe lifetimes, including suspended DNS/startup/retry/cleanup.
 * limitedParallelism alone limits executing threads, not suspended probe counts.
 * @author 雾晚
 */
@OptIn(ExperimentalCoroutinesApi::class)
object NodeTestRunner {
    const val DEFAULT_URL = "http://connectivitycheck.gstatic.com/generate_204"
    const val CONCURRENCY = 4
    const val NETWORK_TIMEOUT_MS = 5000
    const val ATTEMPT_BUDGET_MS = 10000L // Includes temporary core/plugin preparation; network budget stays 5s.
    private val dispatcher = Dispatchers.IO.limitedParallelism(CONCURRENCY)
    private val permits = Semaphore(CONCURRENCY)

    suspend fun measure(url: String = DEFAULT_URL, retryable: (Exception) -> Boolean = { true },
                        attempt: suspend (String, Int) -> Int): Int = permits.withPermit {
        withContext(dispatcher) {
            try {
                measured(url, attempt)
            } catch (cancel: CancellationException) {
                // Parent cancellation is terminal; a temporary plugin's child-scope failure can retry.
                currentCoroutineContext().ensureActive()
                val cause = cancel.cause as? Exception ?: throw cancel
                if (!retryable(cause)) throw cause
                delay(300)
                measured(url, attempt)
            } catch (error: Exception) {
                if (!retryable(error)) throw error
                delay(300)
                measured(url, attempt)
            }
        }
    }
    private suspend fun measured(url: String, attempt: suspend (String, Int) -> Int): Int {
        // Convert only our own per-attempt timeout to a retryable failure; preserve parent cancellation.
        val result = kotlinx.coroutines.withTimeoutOrNull(ATTEMPT_BUDGET_MS) {
            attempt(url, NETWORK_TIMEOUT_MS)
        } ?: throw IOException("节点测速准备或请求超时")
        if (result <= 0) throw IOException("节点测速未返回有效响应")
        return result
    }
}
