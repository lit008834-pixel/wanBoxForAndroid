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
        require(array.length() <= PortableBackup.RECORDS) { "备份记录数量超过限制" }
        return (0 until array.length()).map { index ->
            val bytes = PortableBackup.strictBase64(array.getString(index))
            require(bytes.size >= 4 && bytes.size % 4 == 0) { "备份记录长度无效" }
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
        if (content.has("schemaVersion") || content.has("format")) return PortableBackup.parse(content)
        // @author 雾晚: confirmed historical manual v1 and automatic proxies-without-version exports.
        require(!content.has("version") && content.has("proxies") || content.optInt("version", -1) == 1) { "不支持的备份版本" }
        require(!(content.has("profiles") && content.has("proxies"))) { "备份同时包含冲突的节点字段" }
        val profiles = decode(content, if (content.has("proxies")) "proxies" else "profiles") { parcel ->
            io.nekohasekai.sagernet.fmt.KryoConverters.deserializeStrict(ProxyEntity(), parcel.createByteArray())
        }
        val groups = decode(content, "groups") { parcel ->
            io.nekohasekai.sagernet.fmt.KryoConverters.deserializeStrict(ProxyGroup(), parcel.createByteArray())
        }
        val rules = decode(content, "rules", ParcelizeBridge::createRule)
        val settings = decode(content, "settings", KeyValuePair.CREATOR::createFromParcel)
        return Plan(profiles, groups, rules, settings).also(::validate)
    }

    // @author 雾晚: shared pre-write validation for both portable and historical formats.
    fun validate(plan: Plan) {
        val (profiles, groups, rules, settings) = plan
        require(listOf(profiles, groups, rules, settings).any { it != null }) { "备份没有可恢复的数据" }
        require(listOf(profiles, groups, rules, settings).filterNotNull().all { it.size <= PortableBackup.RECORDS }) { "备份记录数量超过限制" }
        require((profiles == null) == (groups == null)) { "备份缺少节点或分组" }
        groups?.let { require(it.map { g -> g.id }.distinct().size == it.size) { "分组 ID 重复" } }
        profiles?.let { list ->
            require(list.map { it.id }.distinct().size == list.size) { "节点 ID 重复" }
            val groupIds = groups!!.map { it.id }.toSet()
            require(list.all { it.groupId in groupIds }) { "节点引用了不存在的分组" }
            list.forEach { it.requireBean() }
            require(groups!!.all { it.id > 0 } && list.all { it.id > 0 }) { "备份 ID 无效" }
            val ids = list.map { it.id }.toSet()
            fun proxyRef(id: Long) = id <= 0 || id in ids
            require(groups.all { proxyRef(it.frontProxy) && proxyRef(it.landingProxy) }) { "备份分组引用了不存在的节点" }
            list.forEach { p ->
                p.chainBean?.let { require(it.proxies.all { id -> id in ids }) { "备份代理链引用无效" } }
                p.balancerBean?.let { b ->
                    require(b.proxies.all { it in ids } && proxyRef(b.frontProxy) && proxyRef(b.landingProxy)) { "备份负载均衡引用无效" }
                    require((b.targetGroupId <= 0 || b.targetGroupId in groupIds) && b.targetGroupIds.all { it in groupIds }) { "备份负载均衡分组引用无效" }
                }
            }
            rules?.let { require(it.all { r -> proxyRef(r.outbound) }) { "备份路由出站引用无效" } }
        }
        rules?.let { require(it.map { r -> r.id }.distinct().size == it.size) { "规则 ID 重复" } }
        settings?.let { list ->
            require(list.map { it.key }.distinct().size == list.size) { "设置键重复" }
            list.forEach {
                require(it.key.isNotBlank() && it.valueType in 0..6) { "无效设置" }
                require(it.value.size <= 1024 * 1024) { "备份设置过大" }
                val expected = when (it.valueType) { 1 -> 1; 2, 3 -> 4; 4 -> 8; else -> null }
                require(expected == null || it.value.size == expected) { "设置长度无效" }
                if (it.valueType == 6) it.stringSet // Validate length prefixes now, before writes.
            }
        }
    }

    /**
     * A single SQLite connection and rollback journal cover both profile and configuration DBs.
     * Nested independent Room transactions would only be atomic per database.
     */
    @Synchronized
    fun apply(plan: Plan, profile: Boolean, rule: Boolean, setting: Boolean, checkActive: () -> Unit = {}) {
        validate(plan)
        checkActive()
        val room = SagerDatabase.instance
        if (rule && plan.rules != null) {
            val ids = if (profile && plan.profiles != null) plan.profiles.map { it.id }.toSet()
                else room.proxyDao().getAll().map { it.id }.toSet()
            require(plan.rules.all { it.outbound <= 0 || it.outbound in ids }) { "备份路由引用的节点不在目标设备，请同时恢复配置" }
        }
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
