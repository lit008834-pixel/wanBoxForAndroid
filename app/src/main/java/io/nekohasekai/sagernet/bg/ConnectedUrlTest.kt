// @author 雾晚
package io.nekohasekai.sagernet.bg

/** Keeps root dispatch ahead of the lazy in-process box lookup. */
internal object ConnectedUrlTest {
    fun <T> measure(connected: Boolean, url: String, timeoutMs: Int,
                   rootProbe: ((String, Int) -> Int)?, core: () -> T?,
                   coreProbe: (T, String, Int) -> Int): Int {
        if (!connected) return 0
        if (rootProbe != null) return rootProbe(url, timeoutMs)
        val activeCore = core() ?: error("core not started")
        return coreProbe(activeCore, url, timeoutMs)
    }

    fun guardedRoot(ready: () -> Boolean, probe: () -> Int): Int {
        if (!ready()) return 0
        val result = probe()
        return if (ready() && result > 0) result else 0
    }

    fun processAlive(process: Process?): Boolean {
        if (process == null) return false
        return try { process.exitValue(); false } catch (_: IllegalThreadStateException) { true }
    }
}
