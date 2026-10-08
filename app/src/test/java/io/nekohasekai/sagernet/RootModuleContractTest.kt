// @author 雾晚
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.RootModuleClient
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

/** Protocol + Android integration regression checks; not Magisk device tests. @author 雾晚 */
class RootModuleContractTest {
    private fun source(path: String) = File("src/main/java/io/nekohasekai/sagernet/$path").readText()
    @Test fun staleStoppedStatsAreNotAcceptedAsCurrentSuccess() {
        val parsed = RootModuleClient.parseResponse("""{"schemaVersion":1,"ok":true,"state":{"phase":"stopped","stats":{"tx":999},"revision":"new"}}""")
        assertFalse(parsed.connected); assertNull(parsed.stats)
    }
    @Test fun failureAndUnknownProtocolAreRejected() {
        for (json in listOf("""{"schemaVersion":2,"ok":true}""", """{"schemaVersion":1,"ok":false,"error":"revision_conflict"}""", """{"schemaVersion":1,"ok":true,"state":{"phase":"made_up_success"}}""")) {
            try { RootModuleClient.parseResponse(json); fail("accepted failure") } catch (_: IOException) {}
        }
    }
    @Test fun connectedStateIncludesModuleRevisionAndDoesNotRequireAppBox() {
        val parsed = RootModuleClient.parseResponse("""{"schemaVersion":1,"ok":true,"state":{"phase":"connected","revision":"r2","runningRevision":"r1","profileId":7,"profileName":"fixture"}}""")
        assertTrue(parsed.connected); assertEquals("r1", parsed.runningRevision); assertEquals(7L, parsed.profileId)
        val observer = source("bg/RootTunService.kt")
        assertFalse(observer.contains("ProcessBuilder")); assertFalse(observer.contains("noBackupFilesDir"))
        assertFalse(observer.contains("myPid()")); assertFalse(observer.contains("rootProcess"))
        assertTrue(observer.contains("RootModuleClient.call(\"status\")"))
        assertTrue(observer.contains("after.runningRevision == before.runningRevision"))
    }
    @Test fun moduleErrorsAreSafeAndHiddenUiDoesNotPoll() {
        val parsed = RootModuleClient.parseResponse("""{"schemaVersion":1,"ok":true,"state":{"phase":"failed","error":"cgroup_move_failed"}}""")
        assertFalse(parsed.connected); assertEquals("cgroup_move_failed", parsed.error)
        assertEquals("module_operation_failed", RootModuleClient.safeError(IOException("password=fake-secret")))
        val activity = source("ui/MainActivity.kt")
        assertTrue(activity.substringAfter("override fun onStop()").substringBefore("override fun onDestroy()").contains("connection.disconnect(this)"))
        val app = source("SagerNet.kt")
        assertFalse(app.contains("DefaultNetworkListener.start(this"))
        assertTrue(app.contains("val status = client.call(\"status\")"))
    }
    @Test fun rootOnlyRegistrationAndHistoricalSettingNormalization() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("BIND_VPN_SERVICE")); assertFalse(manifest.contains("VpnRequestActivity"))
        val store = source("database/DataStore.kt")
        assertTrue(store.contains("get() = Key.MODE_ROOT")); assertTrue(store.contains("putString(Key.SERVICE_MODE, Key.MODE_ROOT)"))
        val connection = source("bg/SagerConnection.kt")
        assertTrue(connection.contains("RootTunService::class.java")); assertFalse(connection.contains("MODE_PROXY ->"))
        val core = source("fmt/ConfigBuilder.kt")
        assertTrue(core.contains("val useTun = !forTest")); assertTrue(core.contains("auto_route = true"))
        assertTrue(core.contains("auto_redirect = if (isRootTun) true else null"))
    }
    @Test fun noAppPidOrGlobalBootScriptAndModulePreservesSnapshots() {
        val client = source("bg/RootModuleClient.kt")
        assertTrue(client.contains("192L * 1024 * 1024")); assertTrue(client.contains("config apply"))
        assertFalse(client.contains("myPid")); assertFalse(client.contains("librootbox.so"))
        val core = File("../libcore/rootbox.go").readText()
        assertTrue(core.contains("supervisorPID")); assertFalse(core.contains("parentPID"))
        val boot = source("BootReceiver.kt")
        assertFalse(boot.contains("SagerNet.startService()"))
        assertTrue(File("../rootmodule/package/service.sh").readText().contains("__internal boot"))
    }
}
