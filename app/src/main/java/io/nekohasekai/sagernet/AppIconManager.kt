// @author 雾晚: Keep legacy launcher aliases reachable after removing the icon picker.
package io.nekohasekai.sagernet

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import io.nekohasekai.sagernet.ktx.Logs

internal object AppIconStatePolicy {
    fun current(states: Map<AppIcon, Int>): AppIcon {
        return AppIcon.values().drop(1).firstOrNull {
            states[it] == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } ?: AppIcon.NEKOBOX_PLUS
    }
}

object AppIconManager {
    fun current(context: Context): AppIcon {
        return try {
            val packageManager = context.packageManager
            val states = AppIcon.values().associateWith {
                packageManager.getComponentEnabledSetting(it.componentName(context))
            }
            AppIconStatePolicy.current(states)
        } catch (e: Throwable) {
            Logs.w(e)
            AppIcon.NEKOBOX_PLUS
        }
    }

    fun init(context: Context) {
        try {
            val current = current(context)
            if (current == AppIcon.NEKOBOX_PLUS) {
                val packageManager = context.packageManager
                val state = packageManager.getComponentEnabledSetting(AppIcon.NEKOBOX_PLUS.componentName(context))
                if (state != PackageManager.COMPONENT_ENABLED_STATE_ENABLED &&
                    state != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                    packageManager.setComponentEnabledSetting(
                        AppIcon.NEKOBOX_PLUS.componentName(context),
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                }
            }
        } catch (e: Throwable) {
            Logs.w(e)
        }
    }

    private fun AppIcon.componentName(context: Context) =
        ComponentName(context.packageName, aliasClassName)
}
