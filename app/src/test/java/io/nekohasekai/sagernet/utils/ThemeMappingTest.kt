// @author 雾晚
package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.R
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeMappingTest {
    @Test
    fun everyPresetAndCustomThemeHasAnAppAndDialogStyle() {
        for (theme in Theme.MONET..Theme.LIGHT_GRAY) {
            assertTrue(Theme.isSupportedTheme(theme))
            if (theme != Theme.LIGHT_GRAY) {
                assertNotEquals(R.style.Theme_SagerNet_LightGray, Theme.getTheme(theme))
                assertNotEquals(R.style.Theme_SagerNet_Dialog_LightGray, Theme.getDialogTheme(theme))
            }
        }
        assertTrue(Theme.isSupportedTheme(Theme.CUSTOM))
        assertNotEquals(R.style.Theme_SagerNet_LightGray, Theme.getTheme(Theme.CUSTOM))
        assertNotEquals(R.style.Theme_SagerNet_Dialog_LightGray, Theme.getDialogTheme(Theme.CUSTOM))
    }
}
