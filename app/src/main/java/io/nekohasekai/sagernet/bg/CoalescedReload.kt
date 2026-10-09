// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.withTimeoutOrNull

/** One bounded pending edit; an in-flight atomic apply is never cancelled by another edit. @author 雾晚 */
internal class CoalescedReload(
    scope: CoroutineScope,
    private val windowMs: Long = 400,
    private val apply: suspend () -> Unit,
    private val onError: (Exception) -> Unit,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val urgent = AtomicBoolean(false)
    private val worker: Job
    init {
        require(windowMs > 0)
        worker = scope.launch {
            try {
                for (request in requests) {
                    // Quiet time starts again after every edit. No timer runs when idle.
                    while (!urgent.getAndSet(false)) {
                        if (withTimeoutOrNull(windowMs) { requests.receive() } == null) break
                    }
                    try { apply() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { onError(e) }
                }
            } finally { requests.cancel() }
        }
        // Cancellation may happen before the coroutine body starts (and its finally
        // runs). Close the channel on Job completion as well; reject orphan requests.
        worker.invokeOnCompletion { requests.cancel() }
    }

    fun request(immediate: Boolean = false): Boolean {
        if (!worker.isActive) return false
        if (immediate) urgent.set(true)
        return requests.trySend(Unit).isSuccess
    }
}
