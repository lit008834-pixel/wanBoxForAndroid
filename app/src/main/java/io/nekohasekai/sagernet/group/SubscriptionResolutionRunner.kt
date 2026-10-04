// @author 雾晚
package io.nekohasekai.sagernet.group

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Owns subscription DNS children without changing resolver selection. @author 雾晚 */
@OptIn(ExperimentalCoroutinesApi::class)
internal object SubscriptionResolutionRunner {
    const val CONCURRENCY = 5
    private val dispatcher = Dispatchers.IO.limitedParallelism(CONCURRENCY)
    private val permits = Semaphore(CONCURRENCY)

    suspend fun <T, R> run(
        items: List<T>,
        resolve: suspend (T) -> R,
        onResolved: (T, R) -> Unit,
        onFailure: (Exception) -> Unit,
        onFinished: suspend () -> Unit
    ) = coroutineScope {
        items.map { item ->
            launch(dispatcher) {
                // A permit spans suspensions too, and is shared by concurrent subscriptions.
                permits.withPermit {
                    currentCoroutineContext().ensureActive()
                    try {
                        val result = resolve(item)
                        // Blocking system DNS may finish after cancellation; never apply that result.
                        currentCoroutineContext().ensureActive()
                        onResolved(item, result)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        onFailure(error)
                    }
                    currentCoroutineContext().ensureActive()
                    onFinished()
                }
            }
        }.joinAll()
    }
}
