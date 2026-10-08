// @author 雾晚
package io.nekohasekai.sagernet.bg

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.text.format.Formatter
import io.nekohasekai.sagernet.ktx.Logs
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ui.SwitchActivity
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher

/**
 * @author 雾晚
 * User can customize visibility of notification since Android 8.
 * The default visibility:
 *
 * Android 8.x: always visible due to system limitations
 * VPN:         always invisible because of VPN notification/icon
 * Other:       always visible
 *
 * See also: https://github.com/aosp-mirror/platform_frameworks_base/commit/070d142993403cc2c42eca808ff3fafcee220ac4
 */
class ServiceNotification(
    private val service: BaseService.Interface, title: String,
    channel: String, visible: Boolean = false,
) : BroadcastReceiver() {
    companion object {
        const val notificationId = 1
        val flags =
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0) or PendingIntent.FLAG_UPDATE_CURRENT

        fun genTitle(ent: ProxyEntity): String {
            val gn = if (DataStore.showGroupInNotification)
                SagerDatabase.groupDao.getById(ent.groupId)?.displayName() else null
            return if (gn == null) ent.displayName() else "[$gn] ${ent.displayName()}"
        }
    }

    var listenPostSpeed = SagerNet.power.isInteractive

    @Volatile private var destroyed = false
    private val contentCache = NotificationContentCache()
    private var lastSpeed: SpeedDisplayData? = null
    private var observedTag = ""
    private var observedProxy: ProxyInstance? = null
    private var tracker = ActiveOutboundTracker(emptyMap())
    private var initialTitle = title
    private var selectedProfile: ProxyEntity? = null

    // @author 雾晚: both runtimes supply an observed leaf; never query an unopened app box.
    suspend fun postActiveOutbound(tag: String) = useBuilder {
        val proxy = service.data.proxy
        if (proxy !== observedProxy) {
            observedProxy = proxy
            tracker = ActiveOutboundTracker(proxy?.config?.profileTagMap.orEmpty().mapNotNull { (id, nativeTag) ->
                SagerDatabase.proxyDao.getById(kotlin.math.abs(id))?.let { nativeTag to it.displayName() }
            }.toMap())
            lastSpeed = null
            contentCache.clear()
        }
        observedTag = tag
    }

    suspend fun postNotificationSpeedUpdate(stats: SpeedDisplayData) = useBuilder {
        lastSpeed = stats
        renderAndPublish()
    }

    suspend fun postNotificationProfile(profile: ProxyEntity) = useBuilder {
        selectedProfile = profile
        initialTitle = profile.displayName()
        lastSpeed = null
        // A selector change must not retain the previous member while the next sample arrives.
        observedTag = ""
        renderAndPublish()
    }

    suspend fun refreshPreferences() = useBuilder { renderAndPublish() }

    private fun renderAndPublish() {
        if (destroyed) return
        val context = service as Context
        val proxy = service.data.proxy
        val profile = selectedProfile ?: proxy?.profile
        val group = profile?.let { SagerDatabase.groupDao.getById(it.groupId) }
        val strategy = profile?.type == ProxyEntity.TYPE_BALANCER ||
            (proxy != null && proxy.config.selectorGroupId >= 0 && group != null &&
                (DataStore.isGroupUrlTest(group.id) || DataStore.isGroupLoadBalance(group.id)))
        val node = tracker.resolve(observedTag) ?: if (strategy) context.getString(R.string.notification_member_pending)
            else profile?.displayName() ?: initialTitle
        val groupName = if (DataStore.showGroupInNotification) {
            if (profile?.type == ProxyEntity.TYPE_BALANCER) profile.displayName() else group?.displayName()
        } else null
        val speed = lastSpeed
        fun rate(bytes: Long) = context.getString(R.string.speed, Formatter.formatFileSize(context, bytes))
        val proxySpeed = context.getString(R.string.traffic, rate(speed?.txRateProxy ?: 0), rate(speed?.rxRateProxy ?: 0))
        val directSpeed = if (DataStore.showDirectSpeed) context.getString(R.string.notification_direct_speed,
            rate(speed?.txRateDirect ?: 0), rate(speed?.rxRateDirect ?: 0)) else null
        val content = NotificationPresentation.content(node, groupName, strategy, proxySpeed, directSpeed)
        if (!contentCache.changed(content)) return
        builder.setContentTitle(content.title).setContentText(content.text).setSubText(null)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.expanded))
        val manager = NotificationManagerCompat.from(service as Service)
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(notificationId, builder.build())
            contentCache.committed(content)
        } catch (_: SecurityException) {
            // @author 雾晚: revoking notification permission must not disconnect the core.
            Logs.w("Service notification permission unavailable")
        }
    }

    suspend fun postNotificationWakeLockStatus(acquired: Boolean) {
        updateActions()
        useBuilder {
            it.priority = if (acquired) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW
            contentCache.clear()
            renderAndPublish()
        }
    }

    private val builder = NotificationCompat.Builder(service as Context, channel)
        .setWhen(0)
        .setTicker(service.getString(R.string.forward_success))
        .setContentTitle(title)
        .setOnlyAlertOnce(true)
        .setContentIntent(SagerNet.configureIntent(service))
        .setSmallIcon(R.drawable.ic_throne_tile)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(if (visible) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MIN)

    private val buildLock = Any()

    private suspend fun useBuilder(f: (NotificationCompat.Builder) -> Unit) {
        synchronized(buildLock) {
            if (!destroyed) f(builder)
        }
    }

    init {
        service as Context

        Theme.apply(app)
        Theme.apply(service)
        builder.color = service.getColorAttr(R.attr.colorPrimary)

        service.registerReceiver(this, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })

        // @author 雾晚: promote before core initialization; propagate failure to stopRunner.
        try {
            updateActions()
            show()
        } catch (e: RuntimeException) {
            service.unregisterReceiver(this)
            throw e
        }
    }

    private fun updateActions() {
        service as Context
        synchronized(buildLock) {
            val it = builder
            it.clearActions()

            val closeAction = NotificationCompat.Action.Builder(
                0, service.getText(R.string.stop), PendingIntent.getBroadcast(
                    service, 0, Intent(Action.CLOSE).setPackage(service.packageName), flags
                )
            ).setShowsUserInterface(false).build()
            it.addAction(closeAction)

            val switchAction = NotificationCompat.Action.Builder(
                0, service.getString(R.string.action_switch), PendingIntent.getActivity(
                    service, 1, Intent(service, SwitchActivity::class.java), flags
                )
            ).setShowsUserInterface(false).build()
            it.addAction(switchAction)

            val resetUpstreamAction = NotificationCompat.Action.Builder(
                0, service.getString(R.string.reset_connections),
                PendingIntent.getBroadcast(
                    service, 2, Intent(Action.RESET_UPSTREAM_CONNECTIONS).setPackage(service.packageName), flags
                )
            ).setShowsUserInterface(false).build()
            it.addAction(resetUpstreamAction)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_SCREEN_ON || intent.action == Intent.ACTION_SCREEN_OFF) {
            listenPostSpeed = intent.action == Intent.ACTION_SCREEN_ON
            if (listenPostSpeed && service.data.state == BaseService.State.Connected) runOnIoDispatcher { useBuilder {
                lastSpeed = null
                contentCache.clear()
                renderAndPublish()
            } }
        }
    }


    private fun show() = synchronized(buildLock) {
        if (Build.VERSION.SDK_INT >= 34) {
            val type = ForegroundServicePolicy.type(false)
            (service as Service).startForeground(notificationId, builder.build(), type)
        } else {
            (service as Service).startForeground(notificationId, builder.build())
        }
    }

    fun destroy() = synchronized(buildLock) {
        destroyed = true
        lastSpeed = null
        observedTag = ""
        tracker = ActiveOutboundTracker(emptyMap())
        contentCache.clear()
        listenPostSpeed = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            (service as Service).stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            (service as Service).stopForeground(true)
        }
        service.unregisterReceiver(this)
    }
}
