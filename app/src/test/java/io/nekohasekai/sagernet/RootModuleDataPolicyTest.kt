// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.RootModuleDataUpdate
import io.nekohasekai.sagernet.database.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Data selection tests; full serializer/Room roundtrip is an instrumented test. @author 雾晚 */
class RootModuleDataPolicyTest {
    @Test fun installerAcknowledgementFollowsRoomAndModuleCommitAndAppMenuIsRemoved() {
        val source = File("src/main/java/io/nekohasekai/sagernet/bg/RootModuleDataUpdate.kt").readText()
        assertTrue(source.indexOf("RootModuleClient.call(\"data finish\")") < source.indexOf("RootModuleClient.finishInstallerSelection(selectedId)"))
        assertTrue(source.indexOf("RootModuleClient.finishInstallerSelection(selectedId)") < source.indexOf("journal.delete()"))
        assertTrue(source.contains("selected.id != installerId || selected.mode != mode"))
        val settings = File("src/main/java/io/nekohasekai/sagernet/ui/SettingsPreferenceFragment.kt").readText()
        assertFalse(settings.contains("showModuleUpdateData"));assertFalse(settings.contains("rootModuleUpdateData"))
        val activity = File("src/main/java/io/nekohasekai/sagernet/ui/MainActivity.kt").readText()
        assertTrue(activity.contains("RootModuleDataUpdate.applyInstallerSelection()"))
    }
    @Test fun defaultKeepsAllAndNodesOnlyPreservesIdentityAndGroups() {
        val profiles = listOf(ProxyEntity(id=71,groupId=72))
        val groups = listOf(ProxyGroup(id=72,name="fixture"))
        val source = BackupRestore.Plan(profiles,groups,listOf(RuleEntity(id=73)),emptyList())
        assertSame(source,RootModuleDataUpdate.replacement(source,RootModuleDataUpdate.KEEP_ALL))
        val nodes = RootModuleDataUpdate.replacement(source,RootModuleDataUpdate.NODES_ONLY)
        assertSame(profiles,nodes.profiles);assertSame(groups,nodes.groups)
        assertTrue(nodes.rules!!.isEmpty());assertTrue(nodes.settings!!.isEmpty())
        val clean = RootModuleDataUpdate.replacement(source,RootModuleDataUpdate.CLEAN)
        assertTrue(clean.profiles!!.isEmpty());assertTrue(clean.groups!!.isEmpty())
    }
    @Test fun partialBackupsCannotBeUsedToKeepNodesAndNewFlowHasDurableBackupBeforeReset() {
        try { RootModuleDataUpdate.replacement(BackupRestore.Plan(null,null,emptyList(),emptyList()),2);fail() }
        catch (_: IllegalStateException) {}
        val source = File("src/main/java/io/nekohasekai/sagernet/bg/RootModuleDataUpdate.kt").readText()
        assertTrue(source.indexOf("write(AtomicFile(backup), bytes)") < source.indexOf("RootModuleClient.call(\"data prepare\")"))
        assertTrue(source.indexOf("BackupRestore.apply(target") < source.indexOf("RootModuleClient.call(\"data finish\")"))
        assertTrue(source.contains("NonCancellable"));assertTrue(source.contains("journal.delete()"))
        assertFalse(source.contains("clearApplicationUserData"));assertFalse(source.contains("deleteDatabase"))
        val updater = File("src/main/java/io/nekohasekai/sagernet/group/RawUpdater.kt").readText()
        assertTrue(updater.contains("RootModuleDataUpdate.pending()"))
        assertTrue(updater.contains("runInTransaction"))
        assertTrue(updater.contains("subscription_changed"))
    }
}
