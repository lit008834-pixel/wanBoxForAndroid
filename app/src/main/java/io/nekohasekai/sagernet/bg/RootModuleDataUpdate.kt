// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

import android.util.AtomicFile
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.BackupRestore
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.utils.BackupFiles
import io.nekohasekai.sagernet.utils.BackupHelper
import io.nekohasekai.sagernet.utils.BoundedInput
import io.nekohasekai.sagernet.utils.awaitCancellable
import androidx.work.multiprocess.RemoteWorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Applies only installer-confirmed selections with App-owned Room transactions. @author 雾晚 */
object RootModuleDataUpdate {
    const val KEEP_ALL = 0
    const val CLEAN = 1
    const val NODES_ONLY = 2
    private val folder get() = File(SagerNet.application.filesDir, "module-update-backups")
    private val journal get() = AtomicFile(File(folder, "pending.json"))
    fun pending(): Boolean = journal.baseFile.exists() || File(journal.baseFile.path + ".bak").exists()
    fun replacement(source: BackupRestore.Plan, mode: Int): BackupRestore.Plan = when (mode) {
        KEEP_ALL -> source
        CLEAN -> BackupRestore.Plan(emptyList(), emptyList(), emptyList(), emptyList())
        NODES_ONLY -> BackupRestore.Plan(source.profiles ?: error("backup_profiles_missing"),
            source.groups ?: error("backup_groups_missing"), emptyList(), emptyList())
        else -> error("data_mode_invalid")
    }
    private fun write(file: AtomicFile, bytes: ByteArray) {
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Throwable) { file.failWrite(output); throw error }
    }
    fun latestBackup(): File? = folder.listFiles { f -> f.name.startsWith("OwnBox_backup_") && f.extension == "json" }
        ?.maxByOrNull { it.lastModified() }

    // Once destructive work starts, UI destruction must not cancel halfway. A
    // durable journal allows retry after process death; module reconnect is blocked.
    suspend fun prepare(mode: Int, installerId: String? = null): File? = RootModuleClient.dataUpdate {
        withContext(Dispatchers.IO + NonCancellable) {
            require(mode in KEEP_ALL..NODES_ONLY)
            require(installerId == null || installerId.matches(Regex("[a-f0-9]{32}")))
            if (mode == KEEP_ALL && !pending()) return@withContext null
            if (installerId != null && !pending()) {
                val selected = RootModuleClient.call("status").installData ?: return@withContext null
                if (selected.id != installerId || selected.mode != mode) throw IOException("install_data_selection_changed")
            }
            if (DataStore.runningTest) throw IOException("stop_node_tests_before_update")
            if (io.nekohasekai.sagernet.group.GroupUpdater.updating.isNotEmpty()) throw IOException("stop_subscription_updates_before_update")
            val app = SagerNet.application
            RemoteWorkManager.getInstance(app).cancelUniqueWork("SubscriptionUpdater").awaitCancellable()
            check(folder.isDirectory || folder.mkdirs())
            val record = if (pending()) JSONObject(journal.openRead().use { BoundedInput.read(it, 4096).toString(Charsets.UTF_8) })
                else {
                    val bytes = BackupHelper.doBackup()
                    BackupRestore.parse(JSONObject(bytes.toString(Charsets.UTF_8)))
                    val backup = File(folder, BackupFiles.fileName().removeSuffix(".json") + "_${java.util.UUID.randomUUID()}.json")
                    write(AtomicFile(backup), bytes)
                    JSONObject().put("mode", mode).put("backup", backup.name).apply {
                        installerId?.let { put("installerId", it) }
                    }.also {
                        write(journal, it.toString().toByteArray())
                    }
                }
            val name = record.getString("backup")
            require(name.matches(Regex("OwnBox_backup_[a-zA-Z0-9_-]+\\.json")))
            val backup = File(folder, name)
            val source = BackupRestore.parse(JSONObject(backup.inputStream().use {
                BoundedInput.read(it, BoundedInput.JSON_BYTES).toString(Charsets.UTF_8)
            }))
            val target = replacement(source, record.getInt("mode"))
            BackupRestore.validate(target)
            val selectedId = record.optString("installerId")
            if (installerId != null && selectedId != installerId) throw IOException("install_data_selection_changed")
            if (selectedId.isNotEmpty()) {
                require(selectedId.matches(Regex("[a-f0-9]{32}")))
                val selected = RootModuleClient.call("status").installData
                if (selected != null && (selected.id != selectedId || selected.mode != record.getInt("mode")))
                    throw IOException("install_data_selection_changed")
            }
            RootModuleClient.call("data prepare")
            BackupRestore.apply(target, profile = true, rule = true, setting = true)
            RootModuleClient.call("data finish")
            if (selectedId.isNotEmpty()) RootModuleClient.finishInstallerSelection(selectedId)
            journal.delete()
            DataStore.serviceState = BaseService.State.Stopped
            DataStore.initGlobal()
            SubscriptionUpdater.reconfigureUpdater()
            backup
        }
    }

    // Process death is resumed from the original full backup, never from a
    // partially reset database. The installer has already confirmed the choice.
    suspend fun applyInstallerSelection(): Boolean {
        var applied = false
        if (pending()) { prepare(KEEP_ALL); applied = true }
        val state = try { RootModuleClient.call("status") }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { return applied } // Existing service UI handles Root/module availability.
        val selected = state.installData ?: return applied
        return prepare(selected.mode, selected.id) != null || applied
    }
}
