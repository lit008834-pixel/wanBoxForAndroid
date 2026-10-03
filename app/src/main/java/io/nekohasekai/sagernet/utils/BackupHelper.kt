// @author 雾晚
package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import java.io.File
import java.util.*

object BackupHelper {

    fun doBackup(
        profile: Boolean = true,
        rule: Boolean = true,
        setting: Boolean = true
    ): ByteArray {
        val plan = SagerDatabase.instance.runInTransaction(java.util.concurrent.Callable {
            io.nekohasekai.sagernet.database.BackupRestore.Plan(
                if (profile) SagerDatabase.proxyDao.getAll() else null,
                if (profile) SagerDatabase.groupDao.allGroups() else null,
                if (rule) SagerDatabase.rulesDao.allRules() else null,
                if (setting) PublicDatabase.kvPairDao.all() else null,
            )
        })
        return io.nekohasekai.sagernet.database.PortableBackup.encode(plan)
    }

    fun autoBackupLocal(): Boolean {
        return try {
            val baseDir = app.getExternalFilesDir("backup") ?: app.filesDir
            val backupDir = File(baseDir, "auto_backup").apply { mkdirs() }
            val data = doBackup()
            val file = File(backupDir, BackupFiles.fileName())
            file.writeBytes(data)

            // Retain up to 5 newest backups
            val existing = backupDir.listFiles { f ->
                (f.name.startsWith("backup_") || f.name.startsWith("OwnBox_backup_")) && f.name.endsWith(".json")
            }?.sortedBy { it.lastModified() }

            if (existing != null && existing.size > 5) {
                existing.take(existing.size - 5).forEach { it.delete() }
            }
            true
        } catch (e: Exception) {
            Logs.w("Automatic backup failed: ${e.javaClass.simpleName}")
            false
        }
    }
}
