// @author 雾晚
package io.nekohasekai.sagernet

import androidx.room.Room
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.json.JSONObject

class AuditDatabaseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SagerDatabase::class.java.canonicalName!!,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test fun realHistoricalSchemasUpgradeWithoutLosingRows() {
        for (version in 1..3) {
            val name = "audit-migration-$version"
            helper.createDatabase(name, version).apply {
                val extraColumns = if (version >= 2) ", isSelector, frontProxy, landingProxy" else ""
                val extraValues = if (version >= 2) ", 0, -1, -1" else ""
                execSQL("INSERT INTO proxy_groups (id,userOrder,ungrouped,name,type,subscription,`order`$extraColumns) VALUES (7,0,0,'preserved',0,NULL,0$extraValues)")
                close()
            }
            val room = Room.databaseBuilder(context, SagerDatabase::class.java, name)
                .addMigrations(SagerDatabase.MIGRATION_1_2, SagerDatabase.MIGRATION_2_3, SagerDatabase.MIGRATION_9_10)
                .build()
            try {
                room.openHelper.writableDatabase.query("SELECT name,frontProxy,landingProxy FROM proxy_groups WHERE id=7").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("preserved", it.getString(0))
                    assertEquals(-1L, it.getLong(1))
                    assertEquals(-1L, it.getLong(2))
                }
                assertEquals(10, room.openHelper.writableDatabase.version)
            } finally { room.close() }
        }
    }

    @Test fun failedMigrationKeepsOriginalDatabaseAndVersion() {
        val name = "audit-failed-migration"
        helper.createDatabase(name, 1).close()
        val file = context.getDatabasePath(name)
        val room = Room.databaseBuilder(context, SagerDatabase::class.java, name)
            .addMigrations(object : Migration(1, 10) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL("ALTER TABLE proxy_groups ADD COLUMN should_rollback TEXT")
                    throw IllegalStateException("injected migration failure")
                }
            }).build()
        try { room.openHelper.writableDatabase; fail("migration succeeded") }
        catch (_: IllegalStateException) { assertTrue(file.exists()) }
        finally { room.close() }
        android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(1, it.version)
            it.rawQuery("PRAGMA table_info(proxy_groups)", null).use { columns ->
                while (columns.moveToNext()) assertNotEquals("should_rollback", columns.getString(columns.getColumnIndexOrThrow("name")))
            }
        }
    }

    @Test fun lateSettingsFailureRollsBackProfilesGroupsRulesAndSettings() {
        val originalGroups = SagerDatabase.groupDao.allGroups()
        val originalProfiles = SagerDatabase.proxyDao.getAll()
        val originalRules = SagerDatabase.rulesDao.allRules()
        val originalSettings = PublicDatabase.kvPairDao.all()
        try {
            val bean = io.nekohasekai.sagernet.fmt.socks.SOCKSBean().apply { initializeDefaultValues(); serverAddress = "127.0.0.1"; serverPort = 1080 }
            val old = BackupRestore.Plan(listOf(ProxyEntity(id=701, groupId=700).putBean(bean)), listOf(ProxyGroup(id=700, name="old")), listOf(RuleEntity(id=702, name="old rule")),
                listOf(KeyValuePair("audit").put("before")))
            BackupRestore.apply(old, true, true, true)
            val duplicate = listOf(KeyValuePair("audit").put("after"), KeyValuePair("audit").put("duplicate"))
            try {
                BackupRestore.apply(BackupRestore.Plan(emptyList(), listOf(ProxyGroup(id=800,name="new")), emptyList(), duplicate), true, true, true)
                fail("duplicate settings accepted")
            } catch (_: android.database.sqlite.SQLiteConstraintException) {}
            assertEquals(listOf(700L), SagerDatabase.groupDao.allGroups().map { it.id })
            assertEquals("before", PublicDatabase.kvPairDao["audit"]?.string)
            assertEquals(listOf(701L), SagerDatabase.proxyDao.getAll().map { it.id })
            assertEquals(listOf(702L), SagerDatabase.rulesDao.allRules().map { it.id })
            try {
                BackupRestore.parse(JSONObject("""{"version":1,"profiles":[],"groups":["bad"],"settings":[]}"""))
                fail("corrupt group accepted")
            } catch (_: Exception) {}
            assertEquals(listOf(700L), SagerDatabase.groupDao.allGroups().map { it.id })
            assertEquals("before", PublicDatabase.kvPairDao["audit"]?.string)
        } finally {
            BackupRestore.apply(BackupRestore.Plan(originalProfiles, originalGroups, originalRules, originalSettings), true, true, true)
        }
    }
}
