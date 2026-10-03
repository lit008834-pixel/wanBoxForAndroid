// @author 雾晚
package io.nekohasekai.sagernet.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.*
import io.nekohasekai.sagernet.utils.BackupFiles
import io.nekohasekai.sagernet.utils.BackupHelper
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
import java.io.BufferedInputStream
import java.util.concurrent.TimeUnit
import java.io.BufferedOutputStream
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class BackupFragment : NamedFragment(R.layout.layout_backup) {

    private val webDAVClient = SecureNetwork.webDAVClient()
    private var viewBinding: LayoutBackupBinding? = null
    private val binding get() = requireNotNull(viewBinding)

    private fun runOnDefaultDispatcher(block: suspend CoroutineScope.() -> Unit): Job {
        return viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try { block(this) }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                Logs.w("Backup operation failed: ${error.javaClass.simpleName}")
                onMainDispatcher { if (isAdded) safeSnackbar(backupError(error)) }
            }
        }.also { currentJob = it }
    }
    private lateinit var backupData: ByteArray
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
    private val exportSettings = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { data ->
        if (data != null) {
            runOnDefaultDispatcher {
                try {
                    val resolver = (context ?: MessageStore.getCurrentActivity() ?: SagerNet.application).contentResolver
                    val pending = File(app.cacheDir, "pending-backup.json")
                    val bytes = pending.inputStream().use { BoundedInput.read(it) }
                    resolver.openOutputStream(data, "wt")?.use { os -> os.write(bytes) }
                        ?: throw java.io.IOException("无法写入目标文件")
                    val metadata = BackupFiles.metadata(resolver, data)
                    pending.delete()
                    onMainDispatcher {
                        safeSnackbar(getString(R.string.backup_saved_target, metadata.name ?: metadata.provider ?: "SAF"))
                    }
                } catch (e: Exception) {
                    Logs.w("Backup operation failed: ${e.javaClass.simpleName}")
                    onMainDispatcher {
                        safeSnackbar(backupError(e))
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
                File(app.cacheDir, "pending-backup.json").writeBytes(backupData)
                onMainDispatcher {
                    startFilesForResult(
                        exportSettings, BackupFiles.fileName()
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
                    app.cacheDir, BackupFiles.fileName()
                )
                cacheFile.writeBytes(backupData)
                val sharedUri = FileProvider.getUriForFile(app, BuildConfig.APPLICATION_ID + ".cache", cacheFile)
                onMainDispatcher {
                    startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("application/json")
                                .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                .apply { clipData = ClipData.newRawUri("backup", sharedUri) }
                                .putExtra(
                                    Intent.EXTRA_STREAM, sharedUri
                                ), app.getString(R.string.abc_shareactionprovider_share_with)
                        )
                    )
                }

            }
        }

        binding.actionImportFile.setOnClickListener {
            try { importFile.launch(arrayOf("*/*")) } catch (_: Exception) { safeSnackbar(R.string.file_manager_missing) }
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
                val backupData = BackupFiles.archive(doBackup(
                    true,  // 备份配置和分组
                    true,  // 备份路由规则
                    true   // 备份设置
                ))
                
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
                        throw Exception("Upload failed (${response.code}): ${response.message}")
                    }
                    Logs.d("WebDAV backup - Upload successful")
                }

                onMainDispatcher {
                    MessageStore.showMessage(app.getString(R.string.webdav_backup_success))
                }
            } catch (e: Exception) {
                Logs.w("Backup operation failed: ${e.javaClass.simpleName}")

                val errorMessage = try {
                    if (isAdded) {
                        getString(R.string.webdav_backup_failed, backupError(e))
                    } else {
                        app.getString(R.string.webdav_backup_failed, backupError(e))
                    }
                } catch (ex: Exception) {
                    "WebDAV backup failed: ${backupError(e)}"
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
                val path = DataStore.webdavPath?.trim('/')?.takeIf { it.isNotEmpty() } ?: "OwnBox"

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
                        throw Exception("Failed to list directory: ${response.message}")
                    }

                    val responseBody = SecureNetwork.text(response.body) ?: throw Exception("Empty response")
                    
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
                            val fileName = """(?:OwnBox|ownbox|throne)_backup_[^<]*?\d{8}_\d{6}\.(json|zip)""".toRegex(RegexOption.IGNORE_CASE)
                                .find(href)?.value
                            if (fileName != null) {
                                backupFiles.add(fileName)
                            }
                        }
                        if (backupFiles.isNotEmpty()) break
                    }
                    

                    backupFiles.maxByOrNull { fileName ->
                        """(\d{8}_\d{6})""".toRegex().find(fileName)?.value ?: ""
                    } ?: throw Exception("No backup found")
                }

                // 下载最新的备份文件
                val fileUrl = dirUrl.newBuilder()
                    .addPathSegment(latestBackup)
                    .build()

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
                        throw Exception("Download failed (${response.code}): ${response.message}")
                    }
                    response.body?.byteStream()?.use { BoundedInput.read(it, BoundedInput.SOURCE_BYTES) } ?: throw Exception("Empty backup file")
                }

                Logs.d("WebDAV restore - Successfully downloaded backup file, size: ${content.size}")

                // @author 雾晚: WebDAV uses the same bounded content/schema reader as SAF.
                val json = BackupFiles.read(content.inputStream(), latestBackup)
                onMainDispatcher {
                    // 如果 Fragment 已经被销毁，取消恢复操作
                    if (!isAdded) {
                        MessageStore.showMessage(app.getString(R.string.restore_cancelled))
                        return@onMainDispatcher
                    }

                    val import = LayoutImportBinding.inflate(layoutInflater)
                    if (!json.has("profiles") && !json.has("proxies")) {
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
                                    Logs.w("Backup operation failed: ${it.javaClass.simpleName}")
                                    onMainDispatcher {
                                        MessageStore.showMessage(backupError(it))
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
                Logs.w("Backup operation failed: ${e.javaClass.simpleName}")
                onMainDispatcher {
                    MessageStore.showMessage(backupError(e))
                }
            } finally {
                isRestoreInProgress = false
            }
        }
    }

    // @author 雾晚: every export entry point uses the same portable generator.
    private fun doBackup(profile: Boolean, rule: Boolean, setting: Boolean): ByteArray =
        BackupHelper.doBackup(profile, rule, setting)

    val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { file ->
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
                                Logs.w("Backup operation failed: ${it.javaClass.simpleName}")
                                parsed.dbFile.delete()
                                onMainDispatcher {
                                    dialog.dismiss()
                                    MessageStore.showMessage(backupError(it))
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
            Logs.w("Backup operation failed: ${e.javaClass.simpleName}")
            onMainDispatcher {
                MessageStore.showMessage(backupError(e))
            }
        }
    }

    suspend fun startImport(file: Uri) {
        val activity = app
        val metadata = BackupFiles.metadata(app.contentResolver, file)
        try {
            val json = app.contentResolver.openInputStream(file)?.use { BackupFiles.read(it, metadata.name) }
                ?: throw java.io.IOException("无法读取文件，请重新授权文件访问")
            // Only non-sensitive structural diagnostics; never log payloads or URI paths.
            Logs.d("Backup read: mime=${metadata.mime} provider=${metadata.provider} size=${metadata.size} keys=${json.keys().asSequence().toList()}")
            onMainDispatcher {
                val import = LayoutImportBinding.inflate(layoutInflater)
                if (!json.has("profiles") && !json.has("proxies")) {
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
                                Logs.w("Backup operation failed: ${it.javaClass.simpleName}")
                                onMainDispatcher {
                                    dialog.dismiss()
                                    MessageStore.showMessage(backupError(it))
                                }
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        } catch (e: Exception) {
            Logs.w("Backup operation failed: ${e.javaClass.simpleName}")
            onMainDispatcher {
                MessageStore.showMessage(backupError(e))
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

    private fun backupError(error: Throwable): String = when {
        error is SecurityException -> app.getString(R.string.backup_access_error)
        error is IllegalArgumentException && error.message?.startsWith("备份") == true -> error.message!!
        error.message?.startsWith("选择的是日志文件") == true -> app.getString(R.string.backup_choose_json)
        else -> app.getString(R.string.backup_invalid_or_unreadable)
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
