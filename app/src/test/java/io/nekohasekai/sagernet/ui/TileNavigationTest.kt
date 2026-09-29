// @author 雾晚
package io.nekohasekai.sagernet.ui

import android.service.quicksettings.TileService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TileNavigationTest {
    @Test
    fun quickSettingsActionsChooseDifferentDestinations() {
        assertEquals(
            TileNavigation.Destination.CONFIGURATION,
            TileNavigation.destinationFor(TileNavigation.ACTION_OPEN_CONFIGURATION)
        )
        assertEquals(
            TileNavigation.Destination.CUSTOM_ICON,
            TileNavigation.destinationFor(TileService.ACTION_QS_TILE_PREFERENCES)
        )
    }

    @Test
    fun unrelatedLaunchesDoNotOverrideExistingNavigation() {
        assertNull(TileNavigation.destinationFor("android.intent.action.VIEW"))
        assertNull(TileNavigation.destinationFor(null))
    }
}
