// @author 雾晚
package io.nekohasekai.sagernet.ui

import android.app.Dialog
import android.content.Context
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import java.util.function.Consumer

/** Blurs behind a dialog window, never its text or a captured list bitmap. @author 雾晚 */
internal object DialogBlur {
    fun install(dialog: Dialog) {
        if (Build.VERSION.SDK_INT < 31) return
        val window = dialog.window ?: return
        val decor = window.decorView
        if (decor.getTag(R.id.dialog_blur_controller) != null) return
        val controller = Controller(window)
        decor.setTag(R.id.dialog_blur_controller, controller)
        decor.addOnAttachStateChangeListener(controller)
        if (decor.isAttachedToWindow) controller.onViewAttachedToWindow(decor)
    }

    /** Registration belongs to the attached dialog, and is removed on every detach. @author 雾晚 */
    @RequiresApi(31)
    private class Controller(private val window: Window) : View.OnAttachStateChangeListener {
        private val manager = window.context.getSystemService(WindowManager::class.java)
        private var listener: Consumer<Boolean>? = null
        private var originalFlags = 0
        private var originalRadius = 0
        private var attached = false
        private var generation = 0

        override fun onViewAttachedToWindow(view: View) {
            if (attached) return
            attached = true
            val currentGeneration = ++generation
            originalFlags = window.attributes.flags
            originalRadius = window.attributes.blurBehindRadius
            if (DataStore.dialogBlurStrength <= 0 ||
                UiChrome.reduceEffects(window.context)) return
            val callback = Consumer<Boolean> { available ->
                if (attached && generation == currentGeneration) update(available)
            }
            listener = callback
            try {
                manager.addCrossWindowBlurEnabledListener(window.context.mainExecutor, callback)
                update(manager.isCrossWindowBlurEnabled)
            } catch (_: RuntimeException) {
                // An OEM without this optional capability keeps the original Material window.
                release()
                restore()
            }
        }

        private fun update(systemEnabled: Boolean) {
            val radius = DialogBlurPolicy.radiusPx(Build.VERSION.SDK_INT,
                DataStore.dialogBlurStrength, systemEnabled,
                UiChrome.reduceEffects(window.context), window.context.resources.displayMetrics.density)
            val attributes = window.attributes
            attributes.blurBehindRadius = if (radius > 0) radius else originalRadius
            attributes.flags = if (radius > 0) attributes.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                else (attributes.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()) or
                    (originalFlags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = attributes
        }

        private fun release() {
            val callback = listener ?: return
            listener = null
            try { manager.removeCrossWindowBlurEnabledListener(callback) } catch (_: RuntimeException) {
                // Keep dismiss safe when the window manager is already shutting down.
            }
        }

        private fun restore() {
            val attributes = window.attributes
            attributes.blurBehindRadius = originalRadius
            attributes.flags = (attributes.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()) or
                (originalFlags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            window.attributes = attributes
        }

        override fun onViewDetachedFromWindow(view: View) {
            attached = false
            generation++
            release()
            restore()
        }
    }
}

/** Existing builder API and Material appearance with optional window depth. @author 雾晚 */
internal class BlurredAlertDialogBuilder(context: Context) : MaterialAlertDialogBuilder(context) {
    override fun create(): AlertDialog = super.create().also(DialogBlur::install)
}
