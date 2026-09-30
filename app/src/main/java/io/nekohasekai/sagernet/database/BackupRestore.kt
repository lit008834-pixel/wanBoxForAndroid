// @author 雾晚
package io.nekohasekai.sagernet.database

import android.os.Parcel
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject

object BackupRestore {
    data class Plan(
        val profiles: List<ProxyEntity>?, val groups: List<ProxyGroup>?,
        val rules: List<RuleEntity>?, val settings: List<KeyValuePair>?,
    )

    private fun <T> decode(content: JSONObject, key: String, create: (Parcel) -> T): List<T>? {
        if (!content.has(key)) return null
        val array = content.getJSONArray(key)
        require(array.length() <= 100000) { "备份记录数量超过限制" }
        return (0 until array.length()).map { index ->
            val bytes = Util.b64Decode(array.getString(index))
            val parcel = Parcel.obtain()
            try {
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                create(parcel).also { require(parcel.dataAvail() == 0) { "备份记录包含无效数据" } }
            } finally {
                parcel.recycle()
            }
        }
    }

    /** Decode all sections before any destructive statement, including unselected sections. */
    fun parse(content: JSONObject): Plan {
        require(content.getInt("version") == 1) { "不支持的备份版本" }
        val profiles = decode(content, "profiles", ProxyEntity.CREATOR::createFromParcel)
        val groups = decode(content, "groups", ProxyGroup.CREATOR::createFromParcel)
        val rules = decode(content, "rules", ParcelizeBridge::createRule)
        val settings = decode(content, "settings", KeyValuePair.CREATOR::createFromParcel)
        require((profiles == null) == (groups == null)) { "备份缺少节点或分组" }
        groups?.let { require(it.map { g -> g.id }.distinct().size == it.size) { "分组 ID 重复" } }
        profiles?.let { list ->
            require(list.map { it.id }.distinct().size == list.size) { "节点 ID 重复" }
            val groupIds = groups!!.map { it.id }.toSet()
            require(list.all { it.groupId in groupIds }) { "节点引用了不存在的分组" }
            list.forEach { it.requireBean() }
        }
        rules?.let { require(it.map { r -> r.id }.distinct().size == it.size) { "规则 ID 重复" } }
        settings?.let { list ->
            require(list.map { it.key }.distinct().size == list.size) { "设置键重复" }
            list.forEach {
                require(it.key.isNotBlank() && it.valueType in 0..6) { "无效设置" }
                val expected = when (it.valueType) { 1 -> 1; 2, 3 -> 4; 4 -> 8; else -> null }
                require(expected == null || it.value.size == expected) { "设置长度无效" }
                if (it.valueType == 6) it.stringSet // Validate length prefixes now, before writes.
            }
        }
        return Plan(profiles, groups, rules, settings)
    }

    /**
     * A single SQLite connection and rollback journal cover both profile and configuration DBs.
     * Nested independent Room transactions would only be atomic per database.
     */
    @Synchronized
    fun apply(plan: Plan, profile: Boolean, rule: Boolean, setting: Boolean, checkActive: () -> Unit = {}) {
        checkActive()
        val room = SagerDatabase.instance
        val sql = room.openHelper.writableDatabase
        val settings = plan.settings?.takeIf { setting }
        if (settings != null) {
            PublicDatabase.instance.openHelper.writableDatabase // Create/migrate before ATTACH.
            sql.execSQL("ATTACH DATABASE ? AS backup_settings",
                arrayOf(SagerNet.application.getDatabasePath(Key.DB_PUBLIC).absolutePath))
            try {
                sql.query("PRAGMA backup_settings.journal_mode=TRUNCATE").use { cursor ->
                    check(cursor.moveToFirst() && cursor.getString(0).equals("truncate", true))
                }
                check(sql.query("PRAGMA main.journal_mode").use { it.moveToFirst() && !it.getString(0).equals("wal", true) })
            } catch (error: Throwable) {
                sql.execSQL("DETACH DATABASE backup_settings")
                throw error
            }
        }
        try {
            room.runInTransaction {
                checkActive()
                if (profile && plan.profiles != null) {
                    room.proxyDao().reset()
                    room.groupDao().reset()
                    room.groupDao().insert(plan.groups!!)
                    room.proxyDao().insert(plan.profiles)
                }
                checkActive()
                if (rule && plan.rules != null) {
                    room.rulesDao().reset()
                    room.rulesDao().insert(plan.rules)
                }
                checkActive()
                if (settings != null) {
                    sql.execSQL("DELETE FROM backup_settings.KeyValuePair")
                    sql.compileStatement("INSERT INTO backup_settings.KeyValuePair (`key`, valueType, value) VALUES (?, ?, ?)").use { insert ->
                        settings.forEach {
                            checkActive()
                            insert.bindString(1, it.key)
                            insert.bindLong(2, it.valueType.toLong())
                            insert.bindBlob(3, it.value)
                            insert.executeInsert()
                            insert.clearBindings()
                        }
                    }
                }
                checkActive()
            }
        } finally {
            if (settings != null) sql.execSQL("DETACH DATABASE backup_settings")
        }
    }
}
