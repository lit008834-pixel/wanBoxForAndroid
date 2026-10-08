// @author 雾晚
// SPDX-License-Identifier: GPL-3.0-or-later
package io.nekohasekai.sagernet.bg

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.Base64

/** Root-only CLI bridge; the App never owns core processes. @author 雾晚 */
object RootModuleClient {
    // @author 雾晚: this is the manager-owned root module, never App private storage.
    @android.annotation.SuppressLint("SdCardPath")
    const val CLI = "/data/adb/modules/wanbox/bin/wanboxctl"
    private val commands = setOf("status", "module", "logs", "start", "stop", "restart", "reload",
        "config apply", "config validate", "autostart on", "autostart off", "data prepare", "data finish", "data rollback")
    private val changes = Mutex()
    data class InstallSelection(val id: String, val mode: Int)
    data class Status(val phase: String, val revision: String, val runningRevision: String,
        val profileId: Long, val profileName: String, val stats: RootNotificationSample?, val error: String = "",
        val installData: InstallSelection? = null) {
        val connected get() = phase == "connected" && installData == null
        val state get() = if (installData != null) BaseService.State.Stopped else when (phase) {
            "connected" -> BaseService.State.Connected
            "starting" -> BaseService.State.Connecting
            else -> BaseService.State.Stopped
        }
    }
    fun parseResponse(text: String): Status {
        val json = JsonParser.parseString(text).asJsonObject
        if (json["schemaVersion"]?.asInt != 1) throw IOException("module_protocol_mismatch")
        if (json["ok"]?.asBoolean != true) {
            val code = json["error"]?.asString.orEmpty()
            throw IOException(code.takeIf { it.matches(Regex("[a-z_]{1,80}")) } ?: "module_operation_failed")
        }
        val state = json.getAsJsonObject("state") ?: throw IOException("module_status_invalid")
        val phase = state["phase"]?.asString ?: throw IOException("module_status_invalid")
        if (phase !in setOf("connected", "starting", "stopped", "failed", "disabled", "cleanup_required")) throw IOException("module_status_invalid")
        val selection = state["installData"]?.takeUnless { it.isJsonNull }?.let {
            if (!it.isJsonObject) throw IOException("install_data_selection_invalid")
            val entry = it.asJsonObject
            val id = entry["id"]?.asString.orEmpty()
            if (!id.matches(Regex("[a-f0-9]{32}"))) throw IOException("install_data_selection_invalid")
            val mode = when (entry["mode"]?.asString) {
                "fresh" -> RootModuleDataUpdate.CLEAN
                "nodes" -> RootModuleDataUpdate.NODES_ONLY
                else -> throw IOException("install_data_selection_invalid")
            }
            InstallSelection(id, mode)
        }
        val stats = state["stats"]?.takeIf { it.isJsonObject && phase == "connected" && selection == null }
            ?.let { RootNotificationSample.parse(RootNotificationSample.PREFIX + it.toString()) }
        return Status(phase, state["revision"]?.asString.orEmpty(), state["runningRevision"]?.asString.orEmpty(),
            state["profileId"]?.asLong ?: 0, state["profileName"]?.asString.orEmpty(), stats,
            state["error"]?.asString.orEmpty().takeIf { it.matches(Regex("[a-z_]{1,80}")) }.orEmpty(), selection)
    }
    suspend fun call(command: String, input: File? = null): Status = withContext(Dispatchers.IO) {
        require(command in commands || command.matches(Regex("data selection-finish [a-f0-9]{32}")))
        require((command == "config apply" || command == "config validate") == (input != null))
        val process = try { ProcessBuilder("su", "-c", "exec $CLI $command").start() }
            catch (_: IOException) { throw IOException("root_required") }
        try {
            coroutineScope {
                val output = async(Dispatchers.IO) {
                    process.inputStream.use { stream ->
                        val buffer = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        while (true) {
                            val count = stream.read(chunk); if (count < 0) break
                            if (buffer.size() + count > 1_048_576) throw IOException("module_response_too_large")
                            buffer.write(chunk, 0, count)
                        }
                        val bytes = buffer.toByteArray()
                        if (bytes.size > 1_048_576) throw IOException("module_response_too_large")
                        bytes.toString(Charsets.UTF_8)
                    }
                }
                val errors = launch(Dispatchers.IO) { process.errorStream.use { it.copyTo(object : java.io.OutputStream() { override fun write(value: Int) = Unit; override fun write(data: ByteArray, offset: Int, length: Int) = Unit }) } }
                val writer = launch(Dispatchers.IO) { process.outputStream.use { pipe -> input?.inputStream()?.use { it.copyTo(pipe) } } }
                try {
                    withTimeout(if (command in setOf("status", "module", "logs")) 15_000L else 720_000L) { runInterruptible(Dispatchers.IO) { process.waitFor() } }
                    writer.join(); errors.join()
                    try { parseResponse(output.await()) }
                    catch (e: IOException) { throw e }
                    catch (_: Exception) { throw IOException("root_or_module_unavailable") }
                } finally {
                    process.destroy()
                    runCatching { process.inputStream.close() }; runCatching { process.errorStream.close() }; runCatching { process.outputStream.close() }
                    writer.cancel(); errors.cancel(); output.cancel()
                }
            }
        } finally { process.destroy() }
    }
    fun safeError(error: Exception): String = error.message?.takeIf { it.matches(Regex("[a-z_]{1,80}")) } ?: "module_operation_failed"
    suspend fun startOrReload() {
        RootModuleDataUpdate.applyInstallerSelection()
        if (!changes.tryLock()) throw IOException("module_busy")
        try {
        val before = call("status")
        if (RootModuleDataUpdate.pending()) throw IOException("data_update_pending")
        if (before.phase == "disabled") throw IOException("module_disabled_or_missing")
        val profile = SagerDatabase.proxyDao.getById(DataStore.selectedProxy) ?: throw IOException("profile_missing")
        val instance = ProxyInstance(profile)
        val file = File.createTempFile("module-snapshot-", ".json", SagerNet.application.cacheDir)
        try {
            instance.init()
            file.writeText(RootModuleSnapshot(instance, before.revision).build().toString())
            if (file.length() > 192L * 1024 * 1024) throw IOException("snapshot_too_large")
            call("config apply", file)
            call("autostart ${if (DataStore.persistAcrossReboot) "on" else "off"}")
            call("start")
        } finally { file.delete(); instance.close() }
        } finally { changes.unlock() }
    }
    suspend fun stop() = changes.withLock { call("stop") }
    internal suspend fun finishInstallerSelection(id: String) {
        require(id.matches(Regex("[a-f0-9]{32}")))
        call("data selection-finish $id")
    }
    internal suspend fun <T> dataUpdate(action: suspend () -> T): T = changes.withLock { action() }
    suspend fun setAutoStart(enabled: Boolean) = changes.withLock { call("autostart ${if (enabled) "on" else "off"}") }
}

/** Portable snapshot of configs, helpers and resources, independent of App storage. @author 雾晚 */
internal class RootModuleSnapshot(private val instance: ProxyInstance, private val revision: String) {
    private val files = linkedMapOf<String, ByteArray>()
    private val mappedPaths = linkedMapOf<String, String>()
    private var total = 0L
    private fun add(name: String, source: File) {
        if (!source.isFile || source.length() > 64L * 1024 * 1024) throw IOException("resource_missing_or_large")
        if (files.size >= 512) throw IOException("too_many_resources")
        val bytes = source.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer); if (count < 0) break
                if (out.size() + count > 64 * 1024 * 1024) throw IOException("resource_too_large")
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        }
        addBytes(name, bytes); mappedPaths[source.absolutePath] = "../$name"
    }
    private fun addBytes(name: String, bytes: ByteArray) {
        if (files.size >= 512 || files.containsKey(name)) throw IOException("too_many_or_duplicate_resources")
        if (bytes.size > 64L * 1024 * 1024) throw IOException("resource_too_large")
        total += bytes.size
        if (total > 128L * 1024 * 1024) throw IOException("resources_too_large")
        files[name] = bytes
    }
    // @author 雾晚: detect and copy App-private resources into the independent snapshot.
    @android.annotation.SuppressLint("SdCardPath")
    private fun rewrite(value: com.google.gson.JsonElement): com.google.gson.JsonElement {
        if (value.isJsonObject) value.asJsonObject.entrySet().toList().forEach { (key, child) -> value.asJsonObject.add(key, rewrite(child)) }
        else if (value.isJsonArray) for (i in 0 until value.asJsonArray.size()) value.asJsonArray.set(i, rewrite(value.asJsonArray[i]))
        else if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
            var text = value.asString
            if (text == "../cache/cache.db") text = "cache.db"
            mappedPaths[text]?.let { return com.google.gson.JsonPrimitive(it) }
            if (text.startsWith("/data/user/") || text.startsWith("/data/data/") || text.startsWith("/storage/emulated/")) {
                val name = "files/resource-${files.size}"; add(name, File(text))
                return com.google.gson.JsonPrimitive("../$name")
            }
            if (text != value.asString) return com.google.gson.JsonPrimitive(text)
        }
        return value
    }
    fun build(): JsonObject {
        val app = SagerNet.application
        app.externalAssets.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("db", "dat", "srs", "pem") }.forEach {
            val relative = it.relativeTo(app.externalAssets).invariantSeparatorsPath
            val name = if (relative.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._/-]{0,180}"))) relative
                else "resource-" + java.security.MessageDigest.getInstance("SHA-256").digest(relative.toByteArray())
                    .take(8).joinToString("") { byte -> "%02x".format(byte) } + "." + it.extension
            add("assets/$name", it)
        }
        File(app.filesDir, "yacd").let { dir -> if (dir.isDirectory) dir.walkTopDown().filter { it.isFile }.forEach {
            add("files/yacd/" + it.relativeTo(dir).invariantSeparatorsPath, it)
        } }
        val plugins = com.google.gson.JsonArray()
        instance.config.externalIndex.forEach { (chain) -> chain.forEach { (port, profile) ->
            val bean = profile.requireBean()
            val kind = when (bean) {
                is TrojanGoBean -> "trojan-go"; is MieruBean -> "mieru"; is NaiveBean -> "naive"; is HysteriaBean -> "hysteria"
                else -> throw IOException("external_plugin_unsupported")
            }
            val binary = instance.pluginPath["$kind-plugin"]?.path ?: throw IOException("plugin_missing")
            val binaryName = "plugins/$kind/core"
            if (!files.containsKey(binaryName)) {
                add(binaryName, File(binary))
                File(binary).parentFile?.listFiles { item -> item.isFile && item.extension == "so" && item.absolutePath != binary }
                    ?.forEach { add("plugins/$kind/" + it.name, it) }
            }
            val configName = "files/plugin-$port.json"
            val plugin = JsonObject().apply { addProperty("kind", kind); addProperty("binary", binaryName); addProperty("config", configName) }
            if (bean is NaiveBean && bean.certificates.isNotBlank()) {
                val name = "files/plugin-$port.crt"; addBytes(name, bean.certificates.toByteArray()); plugin.addProperty("certificate", name)
            }
            val content = instance.pluginConfigs[port]?.second ?: throw IOException("plugin_config_missing")
            addBytes(configName, rewrite(JsonParser.parseString(content)).toString().toByteArray()); plugins.add(plugin)
        } }
        val config = rewrite(JsonParser.parseString(instance.config.config))
        val encoded = JsonObject(); files.forEach { (name, bytes) -> encoded.addProperty(name, Base64.getEncoder().encodeToString(bytes)) }
        return JsonObject().apply {
            addProperty("schemaVersion", 1); addProperty("expectedRevision", revision); add("config", config); add("files", encoded); add("plugins", plugins)
            addProperty("autoStart", DataStore.persistAcrossReboot); addProperty("performancePriority", DataStore.performancePriorityMode); addProperty("profileId", instance.profile.id); addProperty("profileName", instance.displayProfileName)
        }
    }
}
