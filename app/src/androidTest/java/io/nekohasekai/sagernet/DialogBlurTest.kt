// @author 雾晚
package io.nekohasekai.sagernet

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.BlurredAlertDialogBuilder
import io.nekohasekai.sagernet.ui.DialogBlurPolicy
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.UiChrome
import org.junit.Assert.*
import org.junit.Test

/** Actual dialog windows; tests capability fallback, settings and dismissal, not GPU quality. @author 雾晚 */
class DialogBlurTest {
    @Test fun dialogKeepsMaterialControlsAndRestoresBlurOnDismissAndReshow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val store = DataStore.configurationStore
        val previousEnabled = store.getBoolean(Key.DIALOG_BLUR_ENABLED)
        val previousStrength = store.getInt(Key.DIALOG_BLUR_STRENGTH)
        var activity: Activity? = null
        var dialog: AlertDialog? = null
        try {
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
                MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.runOnMainSync {
                DataStore.dialogBlurEnabled = false
                DataStore.dialogBlurStrength = 12
                var clicks = 0
                dialog = BlurredAlertDialogBuilder(activity!!).setTitle("Synthetic blur fixture")
                    .setMessage("Readable original Material content")
                    .setPositiveButton(android.R.string.ok) { _, _ -> clicks++ }.create()
                dialog!!.show()
                val window = dialog!!.window!!
                assertEquals(0, window.attributes.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                val background = window.decorView.background
                dialog!!.dismiss()
                DataStore.dialogBlurEnabled = true
                dialog!!.show()
                assertSame(background, window.decorView.background)
                dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertEquals(1, clicks)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                if (Build.VERSION.SDK_INT >= 31) assertEquals(0, dialog!!.window!!.attributes.blurBehindRadius)
                assertEquals(0, dialog!!.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                dialog!!.show()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                if (Build.VERSION.SDK_INT >= 31) {
                    val context = activity!!
                    val expected = DialogBlurPolicy.radiusPx(Build.VERSION.SDK_INT, true, 12,
                        context.getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled,
                        UiChrome.reduceEffects(context), context.resources.displayMetrics.density)
                    assertEquals(expected, dialog!!.window!!.attributes.blurBehindRadius)
                }
                dialog!!.dismiss()
            }
        } finally {
            instrumentation.runOnMainSync { dialog?.dismiss(); activity?.finish() }
            if (previousEnabled == null) store.remove(Key.DIALOG_BLUR_ENABLED)
            else store.putBoolean(Key.DIALOG_BLUR_ENABLED, previousEnabled)
            if (previousStrength == null) store.remove(Key.DIALOG_BLUR_STRENGTH)
            else store.putInt(Key.DIALOG_BLUR_STRENGTH, previousStrength)
        }
    }
}
