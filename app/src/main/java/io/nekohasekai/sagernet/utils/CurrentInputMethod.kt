// @author 雾晚
package io.nekohasekai.sagernet.utils

import android.content.Context
import android.provider.Settings
import io.nekohasekai.sagernet.route.InputMethodDirectPolicy

/** Reads the current user's system selection; no hardcoded OEM packages, observer or polling. @author 雾晚 */
object CurrentInputMethod {
    fun identity(context: Context): InputMethodDirectPolicy.Identity? = try {
        val component = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val packageName = InputMethodDirectPolicy.packageName(component)
        if (packageName == null) null else {
            @Suppress("DEPRECATION")
            val uid = context.packageManager.getApplicationInfo(packageName, 0).uid
            InputMethodDirectPolicy.identity(component, uid, context.applicationInfo.uid)
        }
    } catch (_: Exception) {
        // Uninstalled/unreadable input methods cannot create a catch-all direct rule.
        null
    }

    fun label(context: Context, identity: InputMethodDirectPolicy.Identity): String = try {
        @Suppress("DEPRECATION")
        context.packageManager.getApplicationInfo(identity.packageName, 0).loadLabel(context.packageManager).toString()
    } catch (_: Exception) {
        identity.packageName
    }
}
