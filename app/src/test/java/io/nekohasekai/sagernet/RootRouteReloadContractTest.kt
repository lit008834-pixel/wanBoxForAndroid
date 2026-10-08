// @author 雾晚
package io.nekohasekai.sagernet

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Configuration ownership regression; native apply/rollback has separate executable tests. @author 雾晚 */
class RootRouteReloadContractTest {
    private fun source(path: String) = File("src/main/java/io/nekohasekai/sagernet/$path").readText()
    @Test fun ruleSaveAndDeleteRebuildSnapshotInsteadOfRestartingOldConfiguration() {
        val restart = source("SagerNet.kt").substringAfter("fun restartService()").substringBefore("fun stopService()")
        assertTrue(restart.contains("startOrReload(onlyIfRunning = true)"))
        assertFalse(restart.contains("call(\"restart\")"))
        val client = source("bg/RootModuleClient.kt").substringAfter("suspend fun startOrReload(").substringBefore("suspend fun stop()")
        assertTrue(client.contains("if (onlyIfRunning && !before.state.canStop) return"))
        assertTrue(client.contains("if (onlyIfRunning) changes.lock()"))
        assertTrue(client.indexOf("instance.init()") < client.indexOf("call(\"config apply\", file)"))
        for (path in listOf("ui/RouteFragment.kt", "ui/RouteSettingsActivity.kt")) {
            val source = source(path)
            assertTrue(source.contains("SagerNet.restartService()"))
            assertFalse(source.contains("if (DataStore.serviceState.started)"))
        }
    }
    @Test fun foregroundResumeDoesNotKeepExitQueriesOrAddAutomaticLatencyProbe() {
        val stop = source("ui/MainActivity.kt").substringAfter("override fun onStop()").substringBefore("override fun onDestroy()")
        assertTrue(stop.contains("binding.stats.onHostStopped()")); assertTrue(stop.contains("connection.disconnect(this)"))
        val stats = source("widget/StatsBar.kt")
        val cancel = stats.substringAfter("fun onHostStopped()").substringBefore("private data class ProbeKey")
        assertTrue(cancel.contains("landingIpJob?.cancel()")); assertTrue(cancel.contains("LandingIpManager.clearCache()"))
        assertTrue(stats.contains("val queryJob = coroutineContext[Job]"))
        assertTrue(stats.contains("queryJob?.isActive == true && currentState"))
        assertFalse(cancel.contains("testConnection("))
        assertFalse(source("utils/LandingIpManager.kt").contains("req.execute()"))
    }
}
