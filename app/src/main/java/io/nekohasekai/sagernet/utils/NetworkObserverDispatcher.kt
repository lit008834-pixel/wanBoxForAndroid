// @author 雾晚
package io.nekohasekai.sagernet.utils

/** Network notification fan-out; registration stays owned by DefaultNetworkListener. @author 雾晚 */
internal object NetworkObserverDispatcher {
    fun <T> dispatch(listeners: Collection<(T) -> Unit>, value: T, onFailure: (Exception) -> Unit) {
        // A retired observer must not terminate the shared network actor or starve other cores.
        listeners.toList().forEach { listener ->
            try {
                listener(value)
            } catch (error: Exception) {
                onFailure(error)
            }
        }
    }
}
