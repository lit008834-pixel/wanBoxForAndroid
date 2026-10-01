// @author 雾晚
package io.nekohasekai.sagernet.ui

import org.junit.Assert.*
import org.junit.Test

class UiLayoutPolicyTest {
    @Test fun narrowPortraitFallsBackWithoutChangingGridPreference() {
        assertEquals(1, UiLayoutPolicy.columns(true, 312f, 1f))
        assertEquals(2, UiLayoutPolicy.columns(true, 352f, 1f))
        assertEquals(2, UiLayoutPolicy.columns(true, 392f, 1f))
    }
    @Test fun largeFontsGetMoreRoomEvenOnWideScreens() {
        assertEquals(1, UiLayoutPolicy.columns(true, 392f, 1.3f))
        assertEquals(1, UiLayoutPolicy.columns(true, 592f, 2f))
        assertEquals(2, UiLayoutPolicy.columns(true, 792f, 2f))
    }
    @Test fun explicitListRemainsSingleColumnAndSmallFontDoesNotMakeTinyCards() {
        assertEquals(1, UiLayoutPolicy.columns(false, 1000f, 1f))
        assertEquals(1, UiLayoutPolicy.columns(true, 312f, 0.8f))
    }
    @Test fun invalidMeasurementsHaveSafeFallback() {
        for (width in listOf(0f, -100f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(1, UiLayoutPolicy.columns(true, width, 1f))
        }
        assertEquals(1, UiLayoutPolicy.columns(true, 392f, Float.NaN))
    }
    @Test fun tallStatsAndNavigationInsetRemainScrollable() {
        assertEquals(232, UiLayoutPolicy.bottomPadding(88, 168, 40, 24))
        assertEquals(88, UiLayoutPolicy.bottomPadding(88, 0, 40, 0))
        assertEquals(112, UiLayoutPolicy.bottomPadding(88, 24, 40, 24))
    }
    @Test fun reducedEffectsUseOpaqueChrome() {
        assertEquals(255, UiLayoutPolicy.chromeAlpha(true))
        assertEquals(245, UiLayoutPolicy.chromeAlpha(false))
    }
}
