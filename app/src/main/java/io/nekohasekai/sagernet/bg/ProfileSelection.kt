// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Click order is owned by one bounded queue, never racing per-row coroutines. @author 雾晚 */
internal class ProfileSelection(scope: CoroutineScope, save: suspend (Long) -> Unit, onError: (Exception) -> Unit) {
    private val requests = Channel<Long>(Channel.CONFLATED)
    private val worker = scope.launch {
        for (id in requests) {
            try { save(id) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { onError(e) }
        }
    }.also { job -> job.invokeOnCompletion { requests.cancel() } }
    fun request(id: Long): Boolean = id > 0 && worker.isActive && requests.trySend(id).isSuccess
}
