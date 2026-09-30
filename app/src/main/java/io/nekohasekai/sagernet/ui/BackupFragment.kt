// @author 雾晚
package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jakewharton.processphoenix.ProcessPhoenix
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.Executable
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.databinding.LayoutBackupBinding
import io.nekohasekai.sagernet.databinding.LayoutImportBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressBinding
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.*
import androidx.lifecycle.lifecycleScope
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.util.*
import io.nekohasekai.sagernet.utils.BoundedInput
import io.nekohasekai.sagernet.utils.SecureNetwork
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import androidx.annotation.StringRes
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.ktx.snackbar
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream
import java.util.concurrent.TimeUnit
import java.util.zip.Deflater
import java.io.BufferedOutputStream
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class BackupFragment : NamedFragment(R.layout.layout_backup) {

    private val webDAVClient = SecureNetwork.webDAVClient()
    private var viewBinding: LayoutBackupBinding? = null
    private val binding get() = requireNotNull(viewBinding)

    private fun runOnDefaultDispatcher(block: suspend CoroutineScope.() -> Unit): Job {
        return viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO, block = block).also { currentJob = it }
    }
    private lateinit var backupData: ByteArray
    private var isWebDAVBackup = false
    private var isBackupInProgress = false
    private var isRestoreInProgress = false
    private var currentJob: kotlinx.coroutines.Job? = null
    private var snackbar: Snackbar? = null
    private var restoreJob: kotlinx.coroutines.Job? = null

    override fun onDestroyView() {
        webDAVClient.dispatcher.cancelAll()
        webDAVClient.connectionPool.evictAll()
        currentJob?.cancel()
        restoreJob?.cancel()
        currentJob = null
        restoreJob = null
        snackbar?.dismiss()
        snackbar = null
        viewBinding = null
        isRestoreInProgress = false
        isBackupInProgress = false
        super.onDestroyView()
    }

    override fun onDestroy() {
        super.onDestroy()
        currentJob?.cancel()
        currentJob = null
    }

    override fun name0() = app.getString(R.string.backup)

    var content = ""
    private val exportSettings = registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
        if (data != null) {
            runOnDefaultDispatcher {
                try {
                    val resolver = (context ?: MessageStore.getCurrentActivity() ?: SagerNet.application).contentResolver
                    resolver.openOutputStream(data)!!.use { os ->
                        os.write(backupData)
                    }
                    onMainDispatcher {
                        safeSnackbar(R.string.action_export_msg)
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        safeSnackbar(e.readableMessage)
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutBackupBinding.bind(view)
        viewBinding = binding

        binding.actionExport.setOnClickListener {
            val exportProfiles = binding.backupConfigurations.isChecked
            val exportRules = binding.backupRules.isChecked
            val includeSettings = binding.backupSettings.isChecked
            runOnDefaultDispatcher {
                backupData = doBackup(
                    exportProfiles,
                    exportRules,
                    includeSettings
                )
                onMainDispatcher {
                    startFilesForResult(
                        exportSettings, "OwnBox_backup_${Date().toLocaleString()}.json"
                    )
                }
            }
        }

        binding.actionShare.setOnClickListener {
            val exportProfiles = binding.backupConfigurations.isChecked
            val exportRules = binding.backupRules.isChecked
            val includeSettings = binding.backupSettings.isChecked
            runOnDefaultDispatcher {
                backupData = doBackup(
                    exportProfiles,
                    exportRules,
                    includeSettings
                )
                app.cacheDir.mkdirs()
                val cacheFile = File(
                    app.cacheDir, "OwnBox_backup_${Date().toLocaleString()}.json"
                )
                cacheFile.writeBytes(backupData)
                onMainDispatcher {
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("application/json")
                                .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                .putExtra(
                                    Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                                        app, BuildConfig.APPLICATION_ID + ".cache", cacheFile
                                    )
                                ), app.getString(R.string.abc_shareactionprovider_share_with)
                        )
                    )
                }

            }
        }

        binding.actionImportFile.setOnClickListener {
            startFilesForResult(importFile, "*/*")
        }

        binding.actionImportThroneDesktop.setOnClickListener {
            startFilesForResult(importThroneDesktopFile, "*/*")
        }

        setupWebDAV(binding)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.backup)
            .setMessage(R.string.backup_security_notice)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun setupWebDAV(binding: LayoutBackupBinding) {
        binding.webdavSettings.setOnClickListener {
            startActivity(Intent(requireContext(), WebDAVSettingsActivity::class.java))
        }
        
        binding.backupToWebdav.setOnClickListener {
            if (DataStore.webdavServer.isNullOrEmpty()) {
                showMessage(R.string.webdav_server_empty)
                return@setOnClickListener
            }
            backupToWebDAV()
        }
        
        binding.restoreFromWebdav.setOnClickListener {
            if (DataStore.webdavServer.isNullOrEmpty()) {
                showMessage(R.string.webdav_server_empty)
                return@setOnClickListener
            }
            restoreFromWebDAV()
        }
    }

    private fun backupToWebDAV() {
        if (isBackupInProgress) {
            showMessage(R.string.backup_in_progress)
            return
        }
        isBackupInProgress = true
        val activity = app
        runOnDefaultDispatcher {
            try {
                isWebDAVBackup = true
                val backupData = doBackup(
                    true,  // 备份配置和分组
                    true,  // 备份路由规则
                    true   // 备份设置
                )
                isWebDAVBackup = false
                
                val client = webDAVClient

                // 规范化 URL
                val baseUrl = DataStore.webdavServer!!.trimEnd('/')
                val path = DataStore.webdavPath?.trim('/')?.takeIf { it.isNotEmpty() } ?: "OwnBox"

                // 使用英文格式的时间戳作为文件名，修改后缀为 .zip
                val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val version = BuildConfig.VERSION_NAME
                val fileName = "OwnBox_backup_${version}_$timestamp.zip"

                // 确保 baseUrl 是有效的 URL
                if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                    throw Exception("Invalid server URL: must start with http:// or https://")
                }

                // 使用 HttpUrl 构建路径，避免 # 等特殊字符被当作 fragment
                val baseHttpUrl = baseUrl.toHttpUrlOrNull()
                    ?: throw Exception("Invalid server URL: $baseUrl")

                val dirUrl = baseHttpUrl.newBuilder().apply {
                    path.split('/').filter { it.isNotEmpty() }.forEach { segment ->
                        addPathSegment(segment)
                    }
                }.build()

                val fileUrl = dirUrl.newBuilder()
                    .addPathSegment(fileName)
                    .build()

                Logs.d("WebDAV backup - Directory URL: $dirUrl")
                Logs.d("WebDAV backup - File URL: $fileUrl")

                // 先检查目录是否存在
                val propfindRequest = Request.Builder()
                    .url(dirUrl)
                    .method("PROPFIND", null)
                    .header("Authorization", Credentials.basic(
                        DataStore.webdavUsername ?: "",
                        DataStore.webdavPassword ?: ""
                    ))
                    .header("Depth", "0")
                    .build()

                var needCreateDir = false
                client.newCall(propfindRequest).execute().use { response ->
                    Logs.d("WebDAV backup - PROPFIND response: ${response.code}")
                    when (response.code) {
                        404 -> needCreateDir = true
                        207 -> needCreateDir = false // 目录存在
                        401 -> throw Exception("Authentication failed")
                        else -> {
                            if (!response.isSuccessful) {
                                val errorBody = SecureNetwork.text(response.body)
                                Logs.e("WebDAV backup - PROPFIND error: $errorBody")
                                throw Exception("Failed to check directory (${response.code}): ${response.message}")
                            }
                        }
                    }
                }

                // 如果需要，创建目录
                if (needCreateDir) {
                    Logs.d("WebDAV backup - Creating directory")
                    val mkcolRequest = Request.Builder()
                        .url(dirUrl)
                        .method("MKCOL", null)
                        .header("Authorization", Credentials.basic(
                            DataStore.webdavUsername ?: "",
                            DataStore.webdavPassword ?: ""
                        ))
                        .build()

                    client.newCall(mkcolRequest).execute().use { response ->
                        if (!response.isSuccessful) {
                            val errorBody = SecureNetwork.text(response.body)
                            Logs.e("WebDAV backup - MKCOL error: $errorBody")
                            throw Exception("Failed to create directory (${response.code}): ${response.message}")
                        }
                    }
                }

                // 上传文件时使用正确的 Content-Type
                val putRequest = Request.Builder()
                    .url(fileUrl)
                    .put(backupData.toRequestBody("application/zip".toMediaType()))
                    .apply {
                        header("Authorization", Credentials.basic(
                            DataStore.webdavUsername ?: "",
                            DataStore.webdavPassword ?: ""
                        ))
                    }
                    .build()

                client.newCall(putRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = SecureNetwork.text(response.body)
                        Logs.e("WebDAV backup - PUT error: $errorBody")
                        throw Exception("Upload failed (${response.code}): ${response.message}\n$errorBody")
                    }
                    Logs.d("WebDAV backup - Upload successful")
                }

                onMainDispatcher {
                    MessageStore.showMessage(app.getString(R.string.webdav_backup_success))
                }
            } catch (e: Exception) {
                isWebDAVBackup = false  // 确保发生异常时也重置标志
                Logs.w(e)

                val errorMessage = try {
                    if (isAdded) {
                        getString(R.string.webdav_backup_failed, e.message ?: "")
                    } else {
                        app.getString(R.string.webdav_backup_failed, e.message ?: "")
                    }
                } catch (ex: Exception) {
                    "WebDAV backup failed: ${e.message ?: ""}"
                }
                
                onMainDispatcher {
                    MessageStore.showMessage(errorMessage)
                }
            } finally {
                isBackupInProgress = false
            }
        }
    }

    private fun restoreFromWebDAV() {
        if (isRestoreInProgress) {
            showMessage(R.string.restore_in_progress)
            return
        }
        isRestoreInProgress = true
        val activity = app
        restoreJob = runOnDefaultDispatcher {
            try {
                val client = webDAVClient
                val baseUrl = DataStore.webdavServer!!.trimEnd('/')
                val path = DataStore.webdavPath?.trim('/')?.takeIf { it.isNotEmpty() } ?: "Throne"

                if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
                    throw Exception("Invalid server URL: must start with http:// or https://")
                }

                val baseHttpUrl = baseUrl.toHttpUrlOrNull()
                    ?: throw Exception("Invalid server URL: $baseUrl")

                val dirUrl = baseHttpUrl.newBuilder().apply {
                    path.split('/').filter { it.isNotEmpty() }.forEach { segment ->
                        addPathSegment(segment)
                    }
                }.build()

                Logs.d("WebDAV restore - Directory URL: $dirUrl")

                // 先列出目录内容找到最新的备份文件
                val propfindRequest = Request.Builder()
                    .url(dirUrl)
                    .method("PROPFIND", null)
                    .header("Authorization", Credentials.basic(
                        DataStore.webdavUsername ?: "",
                        DataStore.webdavPassword ?: ""
                    ))
                    .header("Depth", "1")
                    .build()

                // 获取最新的备份文件名
                val latestBackup = client.newCall(propfindRequest).execute().use { response ->
                    if (!response.isSuccessful && response.code != 207) {
                        val errorBody = SecureNetwork.text(response.body)
                        Logs.e("WebDAV restore - PROPFIND error: $errorBody")
                        throw Exception("Failed to list directory: ${response.message}")
                    }

                    val responseBody = SecureNetwork.text(response.body) ?: throw Exception("Empty response")
                    Logs.d("WebDAV restore - Directory listing: $responseBody")
                    
                    val patterns = listOf(
                        """<D:href>[^<]*?(?:OwnBox|ownbox|throne)_backup_[^<]*?\d{8}_\d{6}\.(json|zip)</D:href>""".toRegex(RegexOption.IGNORE_CASE),
                        """<d:href>[^<]*?(?:OwnBox|ownbox|throne)_backup_[^<]*?\d{8}_\d{6}\.(json|zip)</d:href>""".toRegex(RegexOption.IGNORE_CASE),
                        """<href>[^<]*?(?:OwnBox|ownbox|throne)_backup_[^<]*?\d{8}_\d{6}\.(json|zip)</href>""".toRegex(RegexOption.IGNORE_CASE)
                    )
                    
                    val backupFiles = mutableListOf<String>()
                    
                    for (pattern in patterns) {
                        val matches = pattern.findAll(responseBody)
                        matches.forEach { match ->
                            val href = match.value
                            Logs.d("WebDAV restore - Found backup file with pattern ${pattern.pattern}: $href")
                            val fileName = """(?:OwnBox|ownbox|throne)_backup_[^<]*?\d{8}_\d{6}\.(json|zip)""".toRegex(RegexOption.IGNORE_CASE)
                                .find(href)?.value
                            if (fileName != null) {
                                backupFiles.add(fileName)
                            }
                        }
                        if (backupFiles.isNotEmpty()) break
                    }
                    
                    Logs.d("WebDAV restore - Found ${backupFiles.size} backup files: ${backupFiles.joinToString()}")

                    backupFiles.maxByOrNull { fileName ->
                        """(\d{8}_\d{6})""".toRegex().find(fileName)?.value ?: ""
                    } ?: throw Exception("No backup found")
                }

                // 下载最新的备份文件
                val fileUrl = dirUrl.newBuilder()
                    .addPathSegment(latestBackup)
                    .build()
                Logs.d("WebDAV restore - File URL: $fileUrl")

                val getRequest = Request.Builder()
                    .url(fileUrl)
                    .get()
                    .header("Authorization", Credentials.basic(
                        DataStore.webdavUsername ?: "",
                        DataStore.webdavPassword ?: ""
                    ))
                    .build()

                val content = client.newCall(getRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = SecureNetwork.text(response.body)
                        Logs.e("WebDAV restore - GET error: $errorBody")
                        throw Exception("Download failed (${response.code}): ${response.message}")
                    }
                    response.body?.byteStream()?.use { BoundedInput.read(it, BoundedInput.SOURCE_BYTES) } ?: throw Exception("Empty backup file")
                }

                Logs.d("WebDAV restore - Successfully downloaded backup file, size: ${content.size}")

                // 根据文件类型处理内容
                val backupContent = if (latestBackup.endsWith(".zip")) {
                    // ZIP 文件处理
                    BoundedInput.backupZip(content.inputStream())
                } else {
                    // JSON 文件处理
                    require(content.size <= BoundedInput.JSON_BYTES) { "备份 JSON 超过安全限制" }
                    content.toString(Charsets.UTF_8)
                }

                // 解析并导入备份数据
                val json = JSONObject(backupContent).also { BackupRestore.parse(it) }
                onMainDispatcher {
                    // 如果 Fragment 已经被销毁，取消恢复操作
                    if (!isAdded) {
                        MessageStore.showMessage(app.getString(R.string.restore_cancelled))
                        return@onMainDispatcher
                    }

                    val import = LayoutImportBinding.inflate(layoutInflater)
                    if (!json.has("profiles")) {
                        import.backupConfigurations.isVisible = false
                    }
                    if (!json.has("rules")) {
                        import.backupRules.isVisible = false
                    }
                    if (!json.has("settings")) {
                        import.backupSettings.isVisible = false
                    }

                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.backup_import)
                        .setView(import.root)
                        .setPositiveButton(R.string.backup_import) { _, _ ->
                            val restoreProfiles = import.backupConfigurations.isChecked
                            val restoreRules = import.backupRules.isChecked
                            val restoreSettings = import.backupSettings.isChecked
                            SagerNet.stopService()

                            val binding = LayoutProgressBinding.inflate(layoutInflater)
                            binding.content.text = getString(R.string.backup_importing)
                            val dialog = AlertDialog.Builder(requireContext())
                                .setView(binding.root)
                                .setCancelable(false)
                                .show()
                            runOnDefaultDispatcher {
                                runCatching {
                                    // 再次检查是否已被取消
                                    if (!isAdded) {
                                        MessageStore.showMessage(app.getString(R.string.restore_cancelled))
                                        return@runOnDefaultDispatcher
                                    }
                                    finishImport(
                                        json,
                                        restoreProfiles,
                                        restoreRules,
                                        restoreSettings
                                    )
                                    ProcessPhoenix.triggerRebirth(
                                        activity, Intent(activity, MainActivity::class.java)
                                    )
                                }.onFailure {
                                    Logs.w(it)
                                    onMainDispatcher {
                                        MessageStore.showMessage(it.readableMessage)
                                    }
                                }

                                onMainDispatcher {
                                    dialog.dismiss()
                                }
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher {
                    MessageStore.showMessage(e.readableMessage)
                }
            } finally {
                isRestoreInProgress = false
            }
        }
    }

    fun Parcelable.toBase64Str(): String {
        val parcel = Parcel.obtain()
        writeToParcel(parcel, 0)
        try {
            return Util.b64EncodeUrlSafe(parcel.marshall())
        } finally {
            parcel.recycle()
        }
    }

    private fun doBackup(
        profile: Boolean,
        rule: Boolean,
        setting: Boolean
    ): ByteArray {
        val out = JSONObject().apply {
            put("version", 1)
            if (profile) {
                put("profiles", JSONArray().apply {
                    SagerDatabase.proxyDao.getAll().forEach {
                        put(it.toBase64Str())
                    }
                })

                put("groups", JSONArray().apply {
                    SagerDatabase.groupDao.allGroups().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
            if (rule) {
                put("rules", JSONArray().apply {
                    SagerDatabase.rulesDao.allRules().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
            if (setting) {
                put("settings", JSONArray().apply {
                    PublicDatabase.kvPairDao.all().forEach {
                        put(it.toBase64Str())
                    }
                })
            }
        }

        val jsonContent = out.toStringPretty()
        return if (isWebDAVBackup) {
            ByteArrayOutputStream().use { bos ->
                ZipOutputStream(bos).use { zos ->
                    zos.setLevel(Deflater.BEST_COMPRESSION)
                    
                    val entry = ZipEntry("OwnBox_backup.json").apply {
                        method = ZipEntry.DEFLATED
                    }
                    
                    // 写入数据
                    zos.putNextEntry(entry)
                    val bytes = jsonContent.toByteArray(Charsets.UTF_8)
                    zos.write(bytes)
                    zos.closeEntry()
                    
                    // 确保所有数据都被写入和压缩
                    zos.finish()
                }
                bos.toByteArray()
            }
        } else {
            // 本地导出和分享功能使用 JSON 格式
            jsonContent.toByteArray()
        }
    }

    val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file != null) {
            runOnDefaultDispatcher {
                startImport(file)
            }
        }
    }

    private val importThroneDesktopFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
            if (file != null) {
                runOnDefaultDispatcher {
                    startImportThroneDesktop(file)
                }
            }
        }

    private suspend fun startImportThroneDesktop(file: Uri) {
        val activity = app
        val fileName = app.contentResolver.query(file, null, null, null, null)
            ?.use { cursor ->
                cursor.moveToFirst()
                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME).let(cursor::getString)
            }
            ?.takeIf { it.isNotBlank() } ?: file.pathSegments.last()
            .substringAfterLast('/')
            .substringAfter(':')

        if (!fileName.endsWith(".thrbackup", ignoreCase = true)) {
            onMainDispatcher {
                val res = (context ?: SagerNet.application).resources
                safeSnackbar(res.getString(R.string.backup_not_throne_desktop, fileName))
            }
            return
        }

        try {
            val bytes = app.contentResolver.openInputStream(file)!!.use { BoundedInput.read(it, BoundedInput.SOURCE_BYTES) }
            val parsed = ThroneDesktopBackupImporter.parse(bytes, app.cacheDir)
            onMainDispatcher {
                if (!isAdded) {
                    parsed.dbFile.delete()
                    return@onMainDispatcher
                }
                val import = LayoutImportBinding.inflate(layoutInflater)
                // reuse import checkboxes; hide unavailable parts
                if (!parsed.hasProfiles) import.backupConfigurations.isVisible = false
                if (!parsed.hasRoutes) import.backupRules.isVisible = false
                if (!parsed.hasSettings) import.backupSettings.isVisible = false

                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.action_import_throne_desktop)
                    .setMessage(R.string.backup_import_throne_desktop_summary)
                    .setView(import.root)
                    .setPositiveButton(R.string.backup_import) { _, _ ->
                        val restoreProfiles = import.backupConfigurations.isChecked
                        val restoreRules = import.backupRules.isChecked
                        val restoreSettings = import.backupSettings.isChecked
                        SagerNet.stopService()
                        val progress = LayoutProgressBinding.inflate(layoutInflater)
                        progress.content.text = getString(R.string.backup_importing)
                        val dialog = AlertDialog.Builder(requireContext())
                            .setView(progress.root)
                            .setCancelable(false)
                            .show()
                        runOnDefaultDispatcher {
                            runCatching {
                                val owner = currentCoroutineContext()
                                ThroneDesktopBackupImporter.import(
                                    parsed,
                                    restoreProfiles && parsed.hasProfiles,
                                    restoreRules && parsed.hasRoutes,
                                    restoreSettings && parsed.hasSettings,
                                    checkActive = { owner.ensureActive() },
                                )
                                triggerFullRestart(app)
                            }.onFailure {
                                Logs.w(it)
                                parsed.dbFile.delete()
                                onMainDispatcher {
                                    dialog.dismiss()
                                    MessageStore.showMessage(it.readableMessage)
                                }
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        parsed.dbFile.delete()
                    }
                    .setOnCancelListener {
                        parsed.dbFile.delete()
                    }
                    .show()
            }
        } catch (e: Exception) {
            Logs.w(e)
            onMainDispatcher {
                MessageStore.showMessage(e.readableMessage)
            }
        }
    }

    suspend fun startImport(file: Uri) {
        val activity = app
        val fileName = app.contentResolver.query(file, null, null, null, null)
            ?.use { cursor ->
                cursor.moveToFirst()
                cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME).let(cursor::getString)
            }
            ?.takeIf { it.isNotBlank() } ?: file.pathSegments.last()
            .substringAfterLast('/')
            .substringAfter(':')

        if (!fileName.endsWith(".json") && !fileName.endsWith(".zip")) {
            onMainDispatcher {
                val res = (context ?: SagerNet.application).resources
                safeSnackbar(res.getString(R.string.backup_not_file, fileName))
            }
            return
        }

        try {
            val content = app.contentResolver.openInputStream(file)!!.use { input ->
                if (fileName.endsWith(".zip")) {
                    BoundedInput.backupZip(input)
                } else {
                    BoundedInput.text(input)
                }
            }

            val json = JSONObject(content).also { BackupRestore.parse(it) }
            onMainDispatcher {
                val import = LayoutImportBinding.inflate(layoutInflater)
                if (!json.has("profiles")) {
                    import.backupConfigurations.isVisible = false
                }
                if (!json.has("rules")) {
                    import.backupRules.isVisible = false
                }
                if (!json.has("settings")) {
                    import.backupSettings.isVisible = false
                }
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.backup_import)
                    .setView(import.root)
                    .setPositiveButton(R.string.backup_import) { _, _ ->
                        val restoreProfiles = import.backupConfigurations.isChecked
                        val restoreRules = import.backupRules.isChecked
                        val restoreSettings = import.backupSettings.isChecked
                        SagerNet.stopService()

                        val binding = LayoutProgressBinding.inflate(layoutInflater)
                        binding.content.text = getString(R.string.backup_importing)
                        val dialog = AlertDialog.Builder(requireContext())
                            .setView(binding.root)
                            .setCancelable(false)
                            .show()
                        runOnDefaultDispatcher {
                            runCatching {
                                finishImport(
                                    json,
                                    restoreProfiles,
                                    restoreRules,
                                    restoreSettings
                                )
                                triggerFullRestart(app)
                            }.onFailure {
                                Logs.w(it)
                                onMainDispatcher {
                                    dialog.dismiss()
                                    MessageStore.showMessage(it.readableMessage)
                                }
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        } catch (e: Exception) {
            Logs.w(e)
            onMainDispatcher {
                MessageStore.showMessage(e.readableMessage)
            }
        }
    }

    suspend fun finishImport(
        content: JSONObject, profile: Boolean, rule: Boolean, setting: Boolean
    ) {
        val plan = BackupRestore.parse(content)
        val context = currentCoroutineContext()
        BackupRestore.apply(plan, profile, rule, setting) { context.ensureActive() }
    }

    private fun showMessage(message: String) {
        MessageStore.showMessage(message)
    }

    private fun showMessage(@StringRes resId: Int) {
        MessageStore.showMessage(requireActivity(), resId)
    }

    private fun showMessage(@StringRes resId: Int, vararg args: Any) {
        MessageStore.showMessage(requireActivity(), resId, *args)
    }

}
