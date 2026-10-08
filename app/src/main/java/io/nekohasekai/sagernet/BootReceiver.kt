package io.nekohasekai.sagernet

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher

class BootReceiver : BroadcastReceiver() {
    companion object {
        private val componentName by lazy { ComponentName(app, BootReceiver::class.java) }
        var enabled: Boolean
            get() = app.packageManager.getComponentEnabledSetting(componentName) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            set(value) = app.packageManager.setComponentEnabledSetting(
                componentName, if (value) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
            )
    }

    override fun onReceive(context: Context, intent: Intent) {
        // @author 雾晚: ignore explicit broadcasts outside the registered boot/update actions.
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        runOnDefaultDispatcher {
            SubscriptionUpdater.reconfigureUpdater()
        }

        // @author 雾晚: module service.sh owns boot and the committed snapshot.
        // Android boot only schedules UI-side subscription updates.
    }
}
