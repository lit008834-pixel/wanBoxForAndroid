// @author 雾晚
package io.nekohasekai.sagernet.ui

import android.Manifest
import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.ImageDecoder
import android.graphics.BitmapFactory
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import io.nekohasekai.sagernet.utils.ImageBudget
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import com.google.zxing.Result
import com.king.zxing.CameraScan
import com.king.zxing.DefaultCameraScan
import com.king.zxing.analyze.QRCodeAnalyzer
import com.king.zxing.util.CodeUtils
import com.king.zxing.util.LogUtils
import com.king.zxing.util.PermissionUtils
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutScannerBinding
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.deduplicateProxies
import io.nekohasekai.sagernet.ktx.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger


class ScannerActivity : ThemedActivity(),
    CameraScan.OnScanResultCallback {

    private fun runOnDefaultDispatcher(block: suspend CoroutineScope.() -> Unit): Job =
        lifecycleScope.launch(Dispatchers.IO, block = block)

    lateinit var binding: LayoutScannerBinding
    lateinit var cameraScan: CameraScan

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 25) getSystemService<ShortcutManager>()!!.reportShortcutUsed("scan")
        binding = LayoutScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 二维码库
        initCameraScan()
        startCamera()
        binding.ivFlashlight.setOnClickListener { toggleTorchState() }
        binding.ivPhotoLibrary.setOnClickListener {
            startFilesForResult(importCodeFile, "image/*")
        }
    }

    val importCodeFile = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) {
        runOnDefaultDispatcher {
            try {
                it.forEachTry { uri ->
                    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        ImageDecoder.decodeBitmap(
                            ImageDecoder.createSource(
                                contentResolver, uri
                            )
                        ) { decoder, info, _ ->
                            val size = ImageBudget.target(info.size.width, info.size.height)
                            decoder.setTargetSize(size.first, size.second)
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            decoder.isMutableRequired = true
                        }
                    } else {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
                        val options = BitmapFactory.Options().apply {
                            inSampleSize = ImageBudget.sample(bounds.outWidth, bounds.outHeight)
                        }
                        contentResolver.openInputStream(uri)!!.use {
                            BitmapFactory.decodeStream(it, null, options)
                        } ?: error("无法读取二维码图片")
                    }
                    try {
                        ensureActive()
                        val result = CodeUtils.parseCodeResult(bitmap)
                        importResult(result, true)
                    } finally {
                        bitmap.recycle()
                    }
                }
                onMainDispatcher { finish() }
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher {
                    Toast.makeText(app, e.readableMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    var finished = AtomicBoolean(false)
    var importedN = AtomicInteger(0)

    /**
     * 接收扫码结果回调
     * @param result 扫码结果
     * @return 返回true表示拦截，将不自动执行后续逻辑，为false表示不拦截，默认不拦截
     */
    override fun onScanResultCallback(result: Result?): Boolean {
        return onScanResultCallback(result, false)
    }

    fun onScanResultCallback(result: Result?, multi: Boolean): Boolean {
        if (!multi && finished.getAndSet(true)) return true
        runOnDefaultDispatcher { importResult(result, multi) }
        return true
    }

    private suspend fun importResult(result: Result?, multi: Boolean) {
            try {
                val text = result?.text ?: throw Exception("QR code not found")
                val rawResults = RawUpdater.parseRaw(text)
                val currentGroupId = DataStore.selectedGroupForImport()
                val targetGroup = SagerDatabase.groupDao.getById(currentGroupId)
                val currentGroup = DataStore.currentGroup()
                val shouldDeduplicate = (targetGroup?.subscription?.deduplication == true) || (currentGroup.subscription?.deduplication == true)
                val results = if (shouldDeduplicate) rawResults?.deduplicateProxies() else rawResults
                if (!results.isNullOrEmpty()) {
                    if (DataStore.selectedGroup != currentGroupId) {
                        DataStore.selectedGroup = currentGroupId
                    }

                    for (profile in results) {
                        ProfileManager.createProfile(currentGroupId, profile)
                        importedN.addAndGet(1)
                    }
                } else {
                    onMainDispatcher {
                        Toast.makeText(app, R.string.action_import_err, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: SubscriptionFoundException) {
                onMainDispatcher {
                    startActivity(Intent(this@ScannerActivity, MainActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        data = e.link.toUri()
                    })
                }
            } catch (e: Throwable) {
                Logs.w(e)
                onMainDispatcher {
                    var text = getString(R.string.action_import_err)
                    text += "\n" + e.readableMessage
                    Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
                }
            }
            if (!multi) onMainDispatcher { finish() }
    }

    /**
     * 初始化CameraScan
     */
    fun initCameraScan() {
        cameraScan = DefaultCameraScan(this, binding.previewView)
        cameraScan.setAnalyzer(QRCodeAnalyzer())
        cameraScan.setOnScanResultCallback(this)
        cameraScan.setNeedAutoZoom(true)
    }

    /**
     * 启动相机预览
     */
    fun startCamera() {
        if (PermissionUtils.checkPermission(this, Manifest.permission.CAMERA)) {
            cameraScan.startCamera()
        } else {
            LogUtils.d("checkPermissionResult != PERMISSION_GRANTED")
            PermissionUtils.requestPermission(
                this, Manifest.permission.CAMERA, CAMERA_PERMISSION_REQUEST_CODE
            )
        }
    }

    /**
     * 释放相机
     */
    private fun releaseCamera() {
        cameraScan.release()
    }

    /**
     * 切换闪光灯状态（开启/关闭）
     */
    protected fun toggleTorchState() {
        val isTorch = cameraScan.isTorchEnabled
        cameraScan.enableTorch(!isTorch)
        binding.ivFlashlight.isSelected = !isTorch
    }

    val CAMERA_PERMISSION_REQUEST_CODE = 0X86

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            requestCameraPermissionResult(permissions, grantResults)
        }
    }

    /**
     * 请求Camera权限回调结果
     * @param permissions
     * @param grantResults
     */
    fun requestCameraPermissionResult(permissions: Array<String>, grantResults: IntArray) {
        if (PermissionUtils.requestPermissionsResult(
                Manifest.permission.CAMERA, permissions, grantResults
            )
        ) {
            startCamera()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        releaseCamera()
        super.onDestroy()
        if (importedN.get() > 0) {
            var text = getString(R.string.action_import_msg)
            text += "\n" + importedN.get() + " profile(s)"
            Toast.makeText(app, text, Toast.LENGTH_LONG).show()
        }
    }
}
