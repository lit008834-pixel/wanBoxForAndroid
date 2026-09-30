// @author 雾晚
package io.nekohasekai.sagernet.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.matrix.roomigrant.GenerateRoomMigrations
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.gson.GsonConverters
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ProxyGroup::class, ProxyEntity::class, RuleEntity::class],
    version = 10,
    autoMigrations = [
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
    ]
)
@TypeConverters(value = [KryoConverters::class, GsonConverters::class])
@GenerateRoomMigrations
abstract class SagerDatabase : RoomDatabase() {

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE proxy_groups ADD COLUMN isSelector INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE proxy_groups ADD COLUMN frontProxy INTEGER NOT NULL DEFAULT -1")
                database.execSQL("ALTER TABLE proxy_groups ADD COLUMN landingProxy INTEGER NOT NULL DEFAULT -1")
                database.execSQL("ALTER TABLE proxy_entities ADD COLUMN shadowTLSBean BLOB")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE proxy_entities ADD COLUMN mieruBean BLOB")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                try {
                    val cursor = database.query("PRAGMA table_info(proxy_entities)")
                    var hasColumn = false
                    val nameIndex = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) {
                        if (nameIndex >= 0 && cursor.getString(nameIndex) == "balancerBean") {
                            hasColumn = true
                            break
                        }
                    }
                    cursor.close()
                    if (!hasColumn) {
                        database.execSQL("ALTER TABLE proxy_entities ADD COLUMN balancerBean BLOB DEFAULT NULL")
                    }
                } catch (e: Throwable) {
                    Logs.w(e)
                    throw e
                }
            }
        }

        @OptIn(DelicateCoroutinesApi::class)
        @Suppress("EXPERIMENTAL_API_USAGE")
        val instance by lazy {
            val app = SagerNet.application
            app.getDatabasePath(Key.DB_PROFILE).parentFile?.mkdirs()
            fun buildDatabase(): SagerDatabase {
                return Room.databaseBuilder(app, SagerDatabase::class.java, Key.DB_PROFILE)
                    .setJournalMode(JournalMode.TRUNCATE)
                    .allowMainThreadQueries()
                    .enableMultiInstanceInvalidation()
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_9_10)
                    .setQueryExecutor(java.util.concurrent.Executors.newFixedThreadPool(2))
                    .build()
            }
            // Room migrations run transactionally. Failed upgrades keep DB/WAL/SHM intact.
            // Opening is deferred so diagnostics/recovery can report an error without deleting data.
            buildDatabase()
        }

        val groupDao get() = instance.groupDao()
        val proxyDao get() = instance.proxyDao()
        val rulesDao get() = instance.rulesDao()

    }

    abstract fun groupDao(): ProxyGroup.Dao
    abstract fun proxyDao(): ProxyEntity.Dao
    abstract fun rulesDao(): RuleEntity.Dao

}
