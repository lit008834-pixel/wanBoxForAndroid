// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.bg.RootModuleClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Node hot-switch policy: runtime selector move first, full apply only as fallback. @author 雾晚 */
class NodeHotSwitchPolicyTest {

    @Test fun moduleConfigAlwaysBuildsTopLevelSelector() {
        val source = File("src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt").readText()
        assertTrue(source.contains("val buildSelector = !forTest && !forExport"))
        assertFalse(source.contains("group?.isSelector == true && !forExport"))
    }

    @Test fun moduleConfigAlwaysExposesClashApiOnLoopback() {
        val source = File("src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt").readText()
        assertTrue(source.contains("if (!forExport || DataStore.enableClashAPI || DataStore.allowAccess)"))
        assertTrue(source.contains("external_controller = \"127.0.0.1:9090\""))
        // The secret must be stable or every snapshot would differ and defeat
        // identicalSnapshotContent.
        assertTrue(source.contains("secret = DataStore.clashApiSecret"))
    }

    @Test fun snapshotPublishesProfileTagsForHotSwitch() {
        val source = File("src/main/java/io/nekohasekai/sagernet/bg/RootModuleClient.kt").readText()
        // Staged via the Files channel (not the strict snapshot schema) so older
        // modules still accept the snapshot.
        assertTrue(source.contains("addBytes(\"profile_tags.json\""))
        assertTrue(source.contains("instance.config.profileTagMap"))
    }

    @Test fun selectNodeCommandIsWhitelistedAndFast() {
        val source = File("src/main/java/io/nekohasekai/sagernet/bg/RootModuleClient.kt").readText()
        assertTrue(source.contains("suspend fun selectNode(profileId: Long): Status"))
        assertTrue(source.contains("call(\"node select \$profileId\")"))
        assertTrue(source.contains("command.startsWith(\"node select \")"))
    }

    @Test fun nodeSelectRejectsInjectionBeforeSpawning() = runBlocking {
        // require() throws before any su process is spawned.
        try { RootModuleClient.call("node select"); fail() } catch (_: IllegalArgumentException) {}
        try { RootModuleClient.call("node select abc"); fail() } catch (_: IllegalArgumentException) {}
        try { RootModuleClient.call("node select 1;id"); fail() } catch (_: IllegalArgumentException) {}
        try { RootModuleClient.call("node select 0"); fail() } catch (_: IllegalArgumentException) {}
        try { RootModuleClient.call("node select -5"); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun profileSelectionTriesHotSwitchBeforeFullReload() {
        val source = File("src/main/java/io/nekohasekai/sagernet/SagerNet.kt").readText()
        val hot = source.indexOf("RootModuleClient.selectNode(id)")
        val fallback = source.indexOf("configurationReload.request(immediate = true)", hot)
        assertTrue(hot >= 0 && fallback > hot)
        assertTrue(source.contains("falling back to full reload"))
    }

    @Test fun wanboxctlExposesNodeSelectCommand() {
        val source = File("rootmodule/cmd/wanboxctl/main.go").readText()
        assertTrue(source.contains("args[0] == \"node\" && args[1] == \"select\""))
        assertTrue(source.contains("r.SelectNode(ctx, id)"))
    }

    @Test fun goSelectNodeHasExplicitFailureContract() {
        val source = File("rootmodule/node_select.go").readText()
        for (code in listOf("module_not_running", "node_tag_not_found", "clash_api_disabled",
            "node_select_failed", "node_select_unsupported", "node_select_not_confirmed")) {
            assertTrue("missing $code", source.contains("\"$code\""))
        }
        // Switch must be confirmed by reading the selector back.
        assertTrue(source.contains("confirmed.Now != pt.Tag"))
        // Only selector outbounds accept a runtime switch; urltest/loadbalance fall back.
        assertTrue(source.contains("strings.EqualFold(current.Type, \"selector\")"))
        // The actually running node is published for UI; the persisted selection
        // stays the source of truth.
        assertTrue(source.contains("s.ProfileID = profileID"))
    }

    @Test fun hotSwitchErrorsMapToDedicatedMessage() {
        val source = File("src/main/java/io/nekohasekai/sagernet/bg/RootModuleErrors.kt").readText()
        assertTrue(source.contains("\"node_tag_not_found\", \"node_select_failed\", \"node_select_not_confirmed\","))
        assertTrue(source.contains("R.string.root_module_node_switch_failed"))
        val en = File("src/main/res/values/root_module.xml").readText()
        val zh = File("src/main/res/values-zh-rCN/root_module.xml").readText()
        assertTrue(en.contains("name=\"root_module_node_switch_failed\""))
        assertTrue(zh.contains("name=\"root_module_node_switch_failed\""))
    }

    @Test fun resolveScopedToDirectBoundIpRules() {
        val source = File("src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt").readText()
        // The blanket resolve must not run for proxy-bound IP rules (poisoned
        // direct-DNS answers would be acted on).
        assertTrue(source.contains("if (rule.outbound == -1L || rule.outbound == -2L)"))
    }

    @Test fun perAppRulesCoverSecondaryUsers() {
        val cache = File("src/main/java/io/nekohasekai/sagernet/utils/PackageCache.kt").readText()
        assertTrue(cache.contains("fun uidsForPackage(packageName: String)"))
        assertTrue(cache.contains("pm list users"))
        val builder = File("src/main/java/io/nekohasekai/sagernet/fmt/ConfigBuilder.kt").readText()
        assertTrue(builder.contains("PackageCache.uidsForPackage(it)"))
        assertTrue(builder.contains("PackageCache.uidsForPackage(pkg)"))
        val client = File("src/main/java/io/nekohasekai/sagernet/bg/RootModuleClient.kt").readText()
        assertTrue(client.contains("PackageCache.refreshMultiUserUids()"))
    }

    @Test fun revisionConflictSurfacedToUser() {
        val source = File("src/main/java/io/nekohasekai/sagernet/bg/RootModuleErrors.kt").readText()
        assertTrue(source.contains("\"revision_conflict\" -> R.string.root_module_revision_conflict"))
        val en = File("src/main/res/values/root_module.xml").readText()
        val zh = File("src/main/res/values-zh-rCN/root_module.xml").readText()
        assertTrue(en.contains("name=\"root_module_revision_conflict\""))
        assertTrue(zh.contains("name=\"root_module_revision_conflict\""))
    }
}
