// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One bounded pending edit; an in-flight atomic apply is never cancelled by another edit. @author 雾晚 */
internal class CoalescedReload(
    scope: CoroutineScope,
    private val windowMs: Long = 400,
    private val apply: suspend () -> Unit,
    private val onError: (Exception) -> Unit,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    init {
        require(windowMs > 0)
        scope.launch {
            try {
                for (request in requests) {
                    // Quiet time starts again after every edit. No timer runs when idle.
                    while (withTimeoutOrNull(windowMs) { requests.receive() } != null) Unit
                    try { apply() }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { onError(e) }
                }
            } finally { requests.cancel() }
        }
    }

    fun request(): Boolean = requests.trySend(Unit).isSuccess
}
