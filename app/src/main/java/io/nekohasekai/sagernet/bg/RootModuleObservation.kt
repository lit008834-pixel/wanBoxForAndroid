// @author 雾晚
package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.*

/** Foreground-bound status sampling must not wait for an Android network callback. @author 雾晚 */
internal object RootModuleObservation {
    suspend fun run(network: suspend () -> Unit, sample: suspend () -> Unit, intervalMs: Long = 2000) = coroutineScope {
        require(intervalMs > 0)
        val networkJob = launch(Dispatchers.IO) {
            try { network() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Module core observes its own physical network. */ }
        }
        try {
            while (isActive) { sample(); delay(intervalMs) }
        } finally { networkJob.cancelAndJoin() }
    }
}
