// @author 雾晚
package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Rapid UI clicks remain ordered and never cancel an atomic core apply. @author 雾晚 */
class ProfileSelectionTest {
    @Test fun rapidReturnToOriginalNodeWinsOverIntermediateClicks() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>(); val saved = mutableListOf<Long>()
        try {
            val selection = ProfileSelection(owner, save = { id ->
                saved += id
                if (saved.size == 1) { first.complete(Unit); release.await() } else done.complete(Unit)
            }, onError = { throw it })
            selection.request(1); withTimeout(2000) { first.await() }
            selection.request(2); selection.request(3); selection.request(1)
            release.complete(Unit); withTimeout(2000) { done.await() }
            assertEquals(listOf(1L, 1L), saved)
        } finally { owner.cancel(); owner.coroutineContext[Job]!!.join() }
    }
    @Test fun failedSelectionDoesNotKillQueueAndCancelledOwnerRejectsNewClicks() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val failed = CompletableDeferred<Unit>(); val recovered = CompletableDeferred<Unit>()
        val selection = ProfileSelection(owner, save = { id ->
            if (id == 1L) throw java.io.IOException("fixture_failure") else recovered.complete(Unit)
        }, onError = { failed.complete(Unit) })
        selection.request(1); withTimeout(2000) { failed.await() }
        assertFalse(selection.request(0)); selection.request(2); withTimeout(2000) { recovered.await() }
        owner.cancel(); owner.coroutineContext[Job]!!.join(); assertFalse(selection.request(3))
    }
    @Test fun selectionUsesModuleTruthAndAcknowledgementsDoNotUndoNewerIntent() {
        val app = File("src/main/java/io/nekohasekai/sagernet/SagerNet.kt").readText()
        assertTrue(app.contains("configurationReload.request(immediate = true)"))
        val ui = File("src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt").readText()
            .substringAfter("private fun selectProfile(proxyEntity: ProxyEntity)").substringBefore("private fun removeProfile")
        assertTrue(ui.contains("SagerNet.selectProfile(proxyEntity.id)"))
        assertFalse(ui.contains("reloadAccess")); assertFalse(ui.contains("BaseService.State.Stopped"))
        val observer = File("src/main/java/io/nekohasekai/sagernet/bg/RootTunService.kt").readText()
        assertTrue(observer.contains("if (changedProfile) data.binder.broadcast { it.cbSelectorUpdate(status.profileId) }"))
        assertTrue(observer.contains("before.profileId != DataStore.selectedProxy"))
        val callback = File("src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt").readText()
            .substringAfter("override fun cbSelectorUpdate(id: Long)").substringBefore("override fun onPreferenceDataStoreChanged")
        assertTrue(callback.contains("if (DataStore.serviceMode != Key.MODE_ROOT) DataStore.selectedProxy = id"))
        val dialog = File("src/main/java/io/nekohasekai/sagernet/ui/NodeSelectDialogActivity.kt").readText()
            .substringAfter("private fun selectNode(").substringBefore("private fun finishWithFade")
        assertTrue(dialog.contains("SagerNet.selectProfile(proxy.id)"))
        assertFalse(dialog.contains("DataStore.currentProfile ="))
        assertFalse(dialog.contains("DataStore.serviceState.started"))
    }
}
