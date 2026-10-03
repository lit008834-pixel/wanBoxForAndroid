// @author 雾晚
package io.nekohasekai.sagernet.database

import com.google.gson.GsonBuilder
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.fmt.AbstractBean
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Modifier
import java.io.StringReader
import java.nio.ByteBuffer
import okio.ByteString.Companion.toByteString
import okio.ByteString.Companion.decodeBase64
import io.nekohasekai.sagernet.utils.BoundedInput

/** Explicit, versioned JSON contract. No Parcel or input-selected runtime classes. @author 雾晚 */
object PortableBackup {
    const val FORMAT = "wanbox.android.backup"
    const val VERSION = 2
    const val RECORDS = 10000
    private val gson = GsonBuilder().serializeNulls().create()
    private val proxyFields = setOf("id", "groupId", "type", "userOrder", "tx", "rx", "status", "ping", "uuid", "error",
        "speedTestMode", "speedTestDownloadBitsPerSecond", "speedTestUploadBitsPerSecond")
    // Classes are selected only from the application's fixed bean registry, never from input.
    private val beanClasses by lazy {
        ProxyEntity::class.java.declaredFields.filter { AbstractBean::class.java.isAssignableFrom(it.type) }.associate { field ->
            val bean = field.type.getDeclaredConstructor().newInstance() as AbstractBean
            ProxyEntity().putBean(bean).type to field.type
        }
    }
    private fun tree(value: Any) = JSONObject(gson.toJson(value))
    private fun fields(obj: JSONObject, allowed: Set<String>) {
        require(obj.keys().asSequence().all { it in allowed }) { "备份含当前版本不支持的字段，请使用兼容版本迁移" }
    }
    private fun plain(value: Any, names: Set<String>): JSONObject {
        val obj = tree(value)
        obj.keys().asSequence().toList().filterNot { it in names }.forEach { obj.remove(it) }
        return obj
    }
    private fun names(type: Class<*>): Set<String> = generateSequence(type) { it.superclass }
        .flatMap { it.declaredFields.asSequence() }.filter { !Modifier.isStatic(it.modifiers) && !Modifier.isTransient(it.modifiers) }
        .map { it.name }.toSet()
    private fun <T> model(obj: JSONObject, type: Class<T>): T {
        fields(obj, names(type))
        return gson.fromJson(obj.toString(), type)
    }
    fun encode(plan: BackupRestore.Plan): ByteArray {
        BackupRestore.validate(plan)
        val root = JSONObject().put("format", FORMAT).put("schemaVersion", VERSION)
        plan.profiles?.let { list -> root.put("profiles", JSONArray(list.map { p ->
            plain(p, proxyFields).put("bean", tree(p.requireBean()))
        })) }
        plan.groups?.let { root.put("groups", JSONArray(it.map(::tree))) }
        plan.rules?.let { root.put("rules", JSONArray(it.map(::tree))) }
        plan.settings?.let { root.put("settings", JSONArray(it.map { kv ->
            JSONObject().put("key", kv.key).put("valueType", kv.valueType)
                .put("valueBase64", kv.value.toByteString().base64())
        })) }
        val bytes = root.toString(2).toByteArray(Charsets.UTF_8)
        require(bytes.size <= BoundedInput.JSON_BYTES) { "备份超过安全大小限制" }
        return bytes
    }
    fun parse(root: JSONObject): BackupRestore.Plan {
        require(root.getString("format") == FORMAT && root.getInt("schemaVersion") == VERSION) { "不支持的备份格式或版本" }
        fields(root, setOf("format", "schemaVersion", "profiles", "groups", "rules", "settings"))
        fun <T> section(key: String, decode: (JSONObject) -> T): List<T>? {
            if (!root.has(key)) return null
            val array = root.getJSONArray(key)
            require(array.length() <= RECORDS) { "备份记录数量超过限制" }
            return (0 until array.length()).map { index ->
                try { decode(array.getJSONObject(index)) }
                catch (_: Exception) { throw IllegalArgumentException("备份 $key 第 ${index + 1} 条记录无效，请重新导出或使用原版本迁移") }
            }
        }
        val profiles = section("profiles") { obj ->
            fields(obj, proxyFields + "bean")
            val metadata = JSONObject(obj.toString()).apply { remove("bean") }
            val p = gson.fromJson(metadata.toString(), ProxyEntity::class.java)
            require(p.uuid != null && p.speedTestMode != null)
            val clazz = beanClasses[p.type] ?: throw IllegalArgumentException("不支持的节点类型")
            val beanJson = obj.getJSONObject("bean")
            fields(beanJson, names(clazz))
            val bean = gson.fromJson(beanJson.toString(), clazz) as AbstractBean
            bean.initializeDefaultValues()
            require(bean.serverPort in 0..65535)
            p.putBean(bean)
        }
        val groups = section("groups") { obj -> model(obj, ProxyGroup::class.java).apply {
            if (obj.has("subscription") && !obj.isNull("subscription")) fields(obj.getJSONObject("subscription"), names(SubscriptionBean::class.java))
            subscription?.initializeDefaultValues()
        } }
        val rules = section("rules") { obj -> model(obj, RuleEntity::class.java).apply {
            require(listOf(name, config, domains, ip, port, sourcePort, network, source, protocol, ruleset).all { it != null })
            require(packages != null && packages.all { it != null })
        } }
        val settings = section("settings") { obj ->
            fields(obj, setOf("key", "valueType", "valueBase64"))
            KeyValuePair(obj.getString("key")).apply {
                valueType = obj.getInt("valueType")
                value = strictBase64(obj.getString("valueBase64"))
            }
        }
        return BackupRestore.Plan(profiles, groups, rules, settings).also(BackupRestore::validate)
    }
    fun strictBase64(text: String): ByteArray {
        require(text.length <= 2 * 1024 * 1024 && text.matches(Regex("[A-Za-z0-9+/_-]*={0,2}"))) { "备份编码无效或过大" }
        return text.decodeBase64()?.toByteArray() ?: throw IllegalArgumentException("备份编码无效")
    }
    /** Reject duplicate keys, excessive nesting/nodes, invalid UTF-8 and trailing text before JSONObject. */
    fun document(bytes: ByteArray): JSONObject = try { validatedDocument(bytes) }
    catch (_: Exception) { throw IllegalArgumentException("备份 JSON 无效、损坏或超过安全限制，请重新导出") }

    private fun validatedDocument(bytes: ByteArray): JSONObject {
        require(bytes.size <= BoundedInput.JSON_BYTES) { "备份超过安全大小限制" }
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        var nodes = 0
        JsonReader(StringReader(text)).use { reader ->
            reader.isLenient = false
            fun walk(depth: Int) {
                require(depth <= 48 && ++nodes <= 250000) { "备份结构超过安全限制" }
                when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        reader.beginObject(); val keys = hashSetOf<String>()
                        while (reader.hasNext()) { require(keys.add(reader.nextName())) { "备份含重复字段" }; walk(depth + 1) }
                        reader.endObject()
                    }
                    JsonToken.BEGIN_ARRAY -> { reader.beginArray(); var count = 0
                        while (reader.hasNext()) { require(++count <= RECORDS) { "备份列表超过安全限制" }; walk(depth + 1) }; reader.endArray() }
                    JsonToken.STRING, JsonToken.NUMBER -> require(reader.nextString().length <= 2 * 1024 * 1024) { "备份字段过大" }
                    JsonToken.BOOLEAN -> reader.nextBoolean()
                    JsonToken.NULL -> reader.nextNull()
                    else -> throw IllegalArgumentException("不是有效的备份 JSON")
                }
            }
            require(reader.peek() == JsonToken.BEGIN_OBJECT) { "不是备份对象" }
            walk(0); require(reader.peek() == JsonToken.END_DOCUMENT) { "备份含多余内容" }
        }
        return JSONObject(text)
    }
}
