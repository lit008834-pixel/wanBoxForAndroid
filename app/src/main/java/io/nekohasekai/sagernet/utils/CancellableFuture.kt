// @author 雾晚
package io.nekohasekai.sagernet.utils

import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cancels the owned work request future with its caller. @author 雾晚 */
suspend fun <T> ListenableFuture<T>.awaitCancellable(): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel(true) }
    addListener({
        if (continuation.isActive) {
            try { continuation.resume(get()) }
            catch (e: Exception) { continuation.resumeWithException(e) }
        }
    }, { it.run() })
}
