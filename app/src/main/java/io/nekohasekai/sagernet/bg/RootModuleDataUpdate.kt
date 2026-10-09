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
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.io.IOException
import io.nekohasekai.sagernet.bg.InstallerDataCommit.Phase
import io.nekohasekai.sagernet.bg.InstallerDataCommit.Stage

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

    internal suspend fun stopSubscriptionWork() {
        try {
            withTimeout(15_000) {
                RemoteWorkManager.getInstance(SagerNet.application).cancelUniqueWork("SubscriptionUpdater").awaitCancellable()
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            throw IOException("subscription_cancel_timeout")
        }
    }

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
            InstallerDataCommit.at(Stage.JOURNAL) { check(folder.isDirectory || folder.mkdirs()) }
            val existing = InstallerDataCommit.at(Stage.JOURNAL) {
                if (pending()) JSONObject(journal.openRead().use { BoundedInput.read(it, 4096).toString(Charsets.UTF_8) }) else null
            }
            val phase = InstallerDataCommit.at(Stage.JOURNAL) {
                existing?.optString("phase", Phase.BACKED_UP.name)?.let(Phase::valueOf) ?: Phase.BACKED_UP
            }
            // A committed database needs only module completion/acknowledgement.
            // Do not let an unrelated test or WorkManager bind failure block that retry.
            if (phase < Phase.APP_COMMITTED) InstallerDataCommit.at(Stage.QUIESCE) {
                if (DataStore.runningTest) throw IOException("stop_node_tests_before_update")
                if (io.nekohasekai.sagernet.group.GroupUpdater.updating.isNotEmpty()) throw IOException("stop_subscription_updates_before_update")
                stopSubscriptionWork()
            }
            val record = existing ?: InstallerDataCommit.at(Stage.BACKUP) {
                val bytes = BackupHelper.doBackup()
                BackupRestore.parse(JSONObject(bytes.toString(Charsets.UTF_8)))
                val backup = File(folder, BackupFiles.fileName().removeSuffix(".json") + "_${java.util.UUID.randomUUID()}.json")
                write(AtomicFile(backup), bytes)
                JSONObject().put("mode", mode).put("backup", backup.name).apply {
                    installerId?.let { put("installerId", it) }
                }.also {
                    write(journal, it.toString().toByteArray(Charsets.UTF_8))
                }
            }
            val (backup, target, selectedId) = InstallerDataCommit.at(Stage.JOURNAL) {
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
                Triple(backup, target, selectedId)
            }
            InstallerDataCommit.run(phase, checkpoint = { next ->
                record.put("phase", next.name)
                write(journal, record.toString().toByteArray(Charsets.UTF_8))
            }, prepare = { RootModuleClient.call("data prepare") },
                restore = { BackupRestore.apply(target, profile = true, rule = true, setting = true) },
                finish = { RootModuleClient.call("data finish") },
                acknowledge = { if (selectedId.isNotEmpty()) RootModuleClient.finishInstallerSelection(selectedId) })
            InstallerDataCommit.at(Stage.REFRESH) {
                DataStore.serviceState = BaseService.State.Stopped
                DataStore.initGlobal()
                SubscriptionUpdater.reconfigureUpdater()
            }
            InstallerDataCommit.at(Stage.JOURNAL) { journal.delete(); check(!pending()) }
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
