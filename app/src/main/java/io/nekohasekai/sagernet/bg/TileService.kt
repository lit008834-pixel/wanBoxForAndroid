// @author 雾晚
package io.nekohasekai.sagernet.bg

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import androidx.annotation.RequiresApi
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.TileNavigation
import io.nekohasekai.sagernet.utils.CustomIconManager
import android.service.quicksettings.TileService as BaseTileService

@RequiresApi(24)
class TileService : BaseTileService(), SagerConnection.Callback {
    companion object {
        const val ACTION_REFRESH_ICON = "io.nekohasekai.sagernet.action.REFRESH_TILE_ICON"
    }

    private val defaultIcon by lazy { Icon.createWithResource(this, R.drawable.ic_throne_tile) }
    private var iconReceiverRegistered = false
    private val iconRefreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_REFRESH_ICON) refreshTileIcon()
        }
    }

    private fun refreshTileIcon() {
        qsTile?.apply {
            icon = getTileIcon()
            updateTile()
        }
    }

    private fun getTileIcon(): Icon {
        val customTileBitmap = if (CustomIconManager.isTileApplied(this)) {
            runCatching { CustomIconManager.loadTileAlphaBitmap(this) }.getOrNull()
        } else null
        return if (customTileBitmap != null) {
            Icon.createWithBitmap(customTileBitmap)
        } else {
            defaultIcon
        }
    }

    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_TILE)
    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) =
        updateTile(state, profileName)

    override fun onServiceConnected(service: ISagerNetService) {
        updateTile(BaseService.State.values()[service.state], service.profileName)
    }

    override fun cbSelectorUpdate(id: Long) {
        val profile = SagerDatabase.proxyDao.getById(id) ?: return
        updateTile(BaseService.State.Connected, profile.displayName())
    }

    override fun onStartListening() {
        super.onStartListening()
        if (!iconReceiverRegistered) {
            val filter = IntentFilter(ACTION_REFRESH_ICON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(iconRefreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(iconRefreshReceiver, filter)
            }
            iconReceiverRegistered = true
        }
        refreshTileIcon()
        connection.connect(this, this)
    }

    override fun onStopListening() {
        if (iconReceiverRegistered) {
            unregisterReceiver(iconRefreshReceiver)
            iconReceiverRegistered = false
        }
        connection.disconnect(this)
        super.onStopListening()
    }

    override fun onClick() {
        if (isLocked) unlockAndRun(this::openConfiguration) else openConfiguration()
    }

    @Suppress("DEPRECATION")
    private fun openConfiguration() {
        val launch = Intent(this, MainActivity::class.java).apply {
            action = TileNavigation.ACTION_OPEN_CONFIGURATION
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pending)
        } else {
            startActivityAndCollapse(launch)
        }
    }

    private fun updateTile(serviceState: BaseService.State, profileName: String?) {
        qsTile?.apply {
            val currentIcon = getTileIcon()
            icon = currentIcon

            // 防御性空值与空白字符串过滤
            val validProfileName = profileName?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

            when (serviceState) {
                BaseService.State.Idle, BaseService.State.Stopped -> {
                    state = Tile.STATE_INACTIVE
                    label = getString(R.string.app_name)
                }
                BaseService.State.Connecting -> {
                    state = Tile.STATE_ACTIVE
                    label = getString(R.string.connecting)
                }

                BaseService.State.Connected -> {
                    state = Tile.STATE_ACTIVE
                    // Primary label = node name so single-line devices (ColorOS etc.) show the node.
                    // If profile name is unavailable, fall back to "已连接".
                    label = validProfileName ?: getString(R.string.tile_connected)
                }

                BaseService.State.Stopping -> {
                    state = Tile.STATE_UNAVAILABLE
                    label = getString(R.string.stopping)
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                setSubtitle(when (serviceState) {
                    // Subtitle = connection status text (secondary row, shown on double-line devices)
                    BaseService.State.Connected -> getString(R.string.tile_connected)
                    BaseService.State.Connecting -> validProfileName
                    BaseService.State.Stopping -> null
                    BaseService.State.Stopped, BaseService.State.Idle -> getString(R.string.not_connected)
                    else -> null
                })
            } else {
                // Pre-Q: no subtitle API; label already shows node name from Connected branch above
            }
            updateTile()
        }
    }

}
