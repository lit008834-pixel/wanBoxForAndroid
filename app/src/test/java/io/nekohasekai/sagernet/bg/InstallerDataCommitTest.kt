// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.File

/** Failure/re-entry tests for the production checkpoint coordinator. @author 雾晚 */
class InstallerDataCommitTest {
    private class Fixture {
        var phase = InstallerDataCommit.Phase.BACKED_UP
        val calls = mutableListOf<String>()
        var fail: String? = null
        suspend fun step(name: String) { calls += name; if (fail == name) throw IOException("fixture_failed") }
        suspend fun run() = InstallerDataCommit.run(phase, checkpoint = { phase = it },
            prepare = { step("prepare") }, restore = { step("restore") },
            finish = { step("finish") }, acknowledge = { step("ack") })
    }

    @Test fun completesInOrderAndDurablyCheckpointsBeforeAcknowledgement() = runBlocking {
        val f = Fixture(); f.run()
        assertEquals(listOf("prepare", "restore", "finish", "ack"), f.calls)
        assertEquals(InstallerDataCommit.Phase.MODULE_FINISHED, f.phase)
    }

    @Test fun everyFailureResumesAtUnfinishedStepWithoutResettingCommittedDatabase() = runBlocking {
        val names = listOf("prepare", "restore", "finish", "ack")
        val stages = listOf(InstallerDataCommit.Stage.MODULE_PREPARE, InstallerDataCommit.Stage.APP_RESTORE,
            InstallerDataCommit.Stage.MODULE_FINISH, InstallerDataCommit.Stage.ACKNOWLEDGE)
        names.forEachIndexed { index, failed ->
            val f = Fixture(); f.fail = failed
            try { f.run(); fail("failure expected") }
            catch (e: InstallerDataCommit.Failure) { assertEquals(stages[index], e.stage) }
            f.calls.clear(); f.fail = null; f.run()
            assertEquals(names.drop(index), f.calls)
        }
    }

    @Test fun checkpointFailureStopsBeforeNextDestructiveOperation() = runBlocking {
        val calls = mutableListOf<String>()
        try {
            InstallerDataCommit.run(InstallerDataCommit.Phase.BACKED_UP,
                checkpoint = { throw IOException("disk_full") }, prepare = { calls += "prepare" },
                restore = { calls += "restore" }, finish = { calls += "finish" }, acknowledge = { calls += "ack" })
            fail()
        } catch (e: InstallerDataCommit.Failure) { assertEquals(InstallerDataCommit.Stage.JOURNAL, e.stage) }
        assertEquals(listOf("prepare"), calls)
    }

    @Test fun diagnosticDoesNotExposeRawBackupOrExceptionText() = runBlocking {
        try {
            InstallerDataCommit.at(InstallerDataCommit.Stage.BACKUP) { throw IOException("fixture://user:password@private.invalid") }
            fail()
        } catch (e: InstallerDataCommit.Failure) {
            assertEquals("data_update_failed", e.message)
            assertEquals(InstallerDataCommit.Stage.BACKUP, e.stage)
        }
    }

    @Test fun cancellationIsNotMisreportedAsDataFailure() = runBlocking {
        val cancel = CancellationException("fixture")
        try { InstallerDataCommit.at(InstallerDataCommit.Stage.QUIESCE) { throw cancel }; fail() }
        catch (e: CancellationException) { assertSame(cancel, e) }
    }

    @Test fun pendingAndBusyStatesAreNotReportedAsMissingRoot() {
        assertEquals(R.string.root_module_data_pending, RootModuleErrors.resource("install_data_update_pending"))
        assertEquals(R.string.root_module_data_test_busy, RootModuleErrors.resource("stop_node_tests_before_update"))
        assertEquals(R.string.root_module_data_subscription_busy, RootModuleErrors.resource("subscription_cancel_timeout"))
        assertEquals(R.string.root_module_data_selection_error, RootModuleErrors.resource("install_data_selection_changed"))
        assertEquals(R.string.root_module_data_journal_corrupt, RootModuleErrors.resource("installer_journal_corrupt"))
        assertEquals(R.string.root_module_action_failed, RootModuleErrors.resource("root_required"))
    }

    @Test fun cancelledBatchGuardAndBothWorkerServicesHaveLifecycleOwners() {
        val source = File("src/main/java/io/nekohasekai/sagernet/ui/ConfigurationFragment.kt").readText()
        assertEquals(2, Regex("trackBatchTest\\(mainJob, dialog\\)").findAll(source).count())
        assertTrue(source.contains("batchTestJob?.cancel()"))
        assertTrue(source.substringAfter("private fun trackBatchTest").substringBefore("inner class GroupPagerAdapter")
            .contains("job.invokeOnCompletion"))
        val recovery = File("src/main/java/io/nekohasekai/sagernet/bg/RootModuleDataUpdate.kt").readText()
        val ownerCheck = "android.app.Application.getProcessName() != io.nekohasekai.sagernet.BuildConfig.APPLICATION_ID"
        assertTrue(recovery.substringAfter("suspend fun prepare").substringBefore("val existing").contains("requireMainProcess()"))
        assertTrue(recovery.substringAfter("suspend fun applyInstallerSelection").substringBefore("var applied").contains(ownerCheck))
        val xml = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val services = xml.getElementsByTagName("service")
        val android = "http://schemas.android.com/apk/res/android"
        listOf("RemoteWorkerService", "RemoteWorkManagerService").forEach { name ->
            val service = (0 until services.length).map { services.item(it) as org.w3c.dom.Element }
                .single { it.getAttributeNS(android, "name") == "androidx.work.multiprocess.$name" }
            assertEquals(":bg", service.getAttributeNS(android, "process"))
            assertEquals("false", service.getAttributeNS(android, "exported"))
        }
    }
}
