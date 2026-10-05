// @author 雾晚
package io.nekohasekai.sagernet.bg

/** Service-owned locks with acquisition rollback and independent cleanup. @author 雾晚 */
class ServicePowerLocks {
    /** Platform handle operations; created before acquisition. @author 雾晚 */
    interface Lease { fun acquire(); fun release() }
    /** Injectable lock operations for tests/non-platform owners. @author 雾晚 */
    class CallbackLease(private val activate: () -> Unit, private val deactivate: () -> Unit) : Lease {
        override fun acquire() = activate()
        override fun release() = deactivate()
    }
    private val owned = linkedMapOf<String, Lease>()

    @Synchronized fun acquire(key: String, create: () -> Lease) {
        if (key in owned) return
        val lease = create()
        owned[key] = lease // Own the handle before an acquisition can partially fail.
        try {
            lease.acquire()
        } catch (error: Exception) {
            release(key)?.let { if (it !== error) error.addSuppressed(it) }
            throw error
        }
    }

    @Synchronized fun release(key: String): Throwable? {
        val lease = owned[key] ?: return null
        return try {
            lease.release()
            owned.remove(key)
            null
        } catch (error: Exception) {
            error // Keep the handle for the next cleanup attempt.
        }
    }

    @Synchronized fun releaseAll(): Throwable? {
        var failure: Throwable? = null
        owned.keys.toList().asReversed().forEach { key ->
            release(key)?.let { error ->
                if (failure == null) failure = error
                else if (failure !== error) failure!!.addSuppressed(error)
            }
        }
        return failure
    }
}
