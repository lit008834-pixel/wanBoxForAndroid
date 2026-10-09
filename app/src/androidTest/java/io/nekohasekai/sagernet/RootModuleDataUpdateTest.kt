// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.RootModuleDataUpdate
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import android.util.AtomicFile
import io.nekohasekai.sagernet.bg.InstallerDataCommit
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException

/** Real portable serializer and Room restore contract; no Root process required. @author 雾晚 */
class RootModuleDataUpdateTest {
    @Test fun subscriptionCancellationUsesConfiguredBackgroundProcess() = runBlocking {
        val app = SagerNet.application
        val component = android.content.ComponentName(app, "androidx.work.multiprocess.RemoteWorkManagerService")
        val service = app.packageManager.getServiceInfo(component, 0)
        assertEquals(app.packageName + ":bg", service.processName)
        assertFalse(service.exported)
        RootModuleDataUpdate.stopSubscriptionWork()
    }

    @Test fun acknowledgementRetryUsesDurableProgressAndDoesNotResetRoomAgain() = runBlocking {
        val original = BackupRestore.parse(JSONObject(io.nekohasekai.sagernet.utils.BackupHelper.doBackup().toString(Charsets.UTF_8)))
        val folder = File(SagerNet.application.cacheDir, "installer-recovery-test-" + java.util.UUID.randomUUID()).apply { check(mkdirs()) }
        val journal = AtomicFile(File(folder, "progress.json"))
        val node = ProxyEntity(id = 9201, groupId = 9202).putBean(SOCKSBean().apply {
            initializeDefaultValues(); serverAddress = "192.0.2.1"; serverPort = 1080
        })
        try {
            BackupRestore.apply(BackupRestore.Plan(listOf(node), listOf(ProxyGroup(id = 9202, name = "fixture")),
                listOf(RuleEntity(id = 9203, outbound = 9201)), emptyList()), true, true, true)
            // Use the actual export generator, not hand-written backup JSON.
            val bytes = io.nekohasekai.sagernet.utils.BackupHelper.doBackup()
            val target = RootModuleDataUpdate.replacement(BackupRestore.parse(JSONObject(bytes.toString(Charsets.UTF_8))), RootModuleDataUpdate.NODES_ONLY)
            var restoreCount = 0
            var rejectAck = true
            suspend fun run(phase: InstallerDataCommit.Phase) = InstallerDataCommit.run(phase,
                checkpoint = { next ->
                    val stream = journal.startWrite()
                    try { stream.write(JSONObject().put("phase", next.name).toString().toByteArray()); journal.finishWrite(stream) }
                    catch (e: Throwable) { journal.failWrite(stream); throw e }
                }, prepare = {}, restore = { restoreCount++; BackupRestore.apply(target, true, true, true) },
                finish = {}, acknowledge = { if (rejectAck) throw IOException("fixture_failed") })
            try { run(InstallerDataCommit.Phase.BACKED_UP); fail() }
            catch (e: InstallerDataCommit.Failure) { assertEquals(InstallerDataCommit.Stage.ACKNOWLEDGE, e.stage) }
            // A user edit after the committed reset must survive an acknowledgement retry.
            SagerDatabase.rulesDao.insert(listOf(RuleEntity(id = 9204, outbound = 9201)))
            val restoredPhase = journal.openRead().use {
                InstallerDataCommit.Phase.valueOf(JSONObject(it.readBytes().toString(Charsets.UTF_8)).getString("phase"))
            }
            rejectAck = false
            run(restoredPhase)
            assertEquals(1, restoreCount)
            assertEquals(9201L, SagerDatabase.proxyDao.getAll().single().id)
            assertEquals(9204L, SagerDatabase.rulesDao.allRules().single().id)
        } finally { BackupRestore.apply(original, true, true, true); folder.deleteRecursively() }
    }

    @Test fun threePoliciesPreserveOnlyRequestedSectionsAndDatabaseRestores() {
        val original = BackupRestore.parse(JSONObject(io.nekohasekai.sagernet.utils.BackupHelper.doBackup().toString(Charsets.UTF_8)))
        val proxy = ProxyEntity(id=9101,groupId=9102).putBean(SOCKSBean().apply {
            initializeDefaultValues();serverAddress="192.0.2.1";serverPort=1080
        })
        val source = BackupRestore.Plan(listOf(proxy),listOf(ProxyGroup(id=9102,name="fixture")),
            listOf(RuleEntity(id=9103,outbound=9101)),emptyList())
        val decoded = BackupRestore.parse(JSONObject(PortableBackup.encode(source).toString(Charsets.UTF_8)))
        assertSame(decoded,RootModuleDataUpdate.replacement(decoded,RootModuleDataUpdate.KEEP_ALL))
        try {
            val nodes = RootModuleDataUpdate.replacement(decoded,RootModuleDataUpdate.NODES_ONLY)
            BackupRestore.apply(nodes,true,true,true)
            assertEquals(9101L,SagerDatabase.proxyDao.getAll().single().id)
            assertEquals("fixture",SagerDatabase.groupDao.allGroups().single().name)
            assertTrue(SagerDatabase.rulesDao.allRules().isEmpty())
            assertTrue(io.nekohasekai.sagernet.database.preference.PublicDatabase.kvPairDao.all().isEmpty())
            BackupRestore.apply(RootModuleDataUpdate.replacement(decoded,RootModuleDataUpdate.CLEAN),true,true,true)
            assertTrue(SagerDatabase.proxyDao.getAll().isEmpty())
            assertTrue(SagerDatabase.groupDao.allGroups().isEmpty())
        } finally { BackupRestore.apply(original,true,true,true) }
    }
}
