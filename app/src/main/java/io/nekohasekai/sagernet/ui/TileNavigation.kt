// @author 雾晚
package io.nekohasekai.sagernet.ui

import android.service.quicksettings.TileService

internal object TileNavigation {
    const val ACTION_OPEN_CONFIGURATION =
        "io.nekohasekai.sagernet.action.OPEN_CONFIGURATION_FROM_TILE"

    enum class Destination { CONFIGURATION, CUSTOM_ICON }

    fun destinationFor(action: String?): Destination? = when (action) {
        ACTION_OPEN_CONFIGURATION -> Destination.CONFIGURATION
        TileService.ACTION_QS_TILE_PREFERENCES -> Destination.CUSTOM_ICON
        else -> null
    }
}
