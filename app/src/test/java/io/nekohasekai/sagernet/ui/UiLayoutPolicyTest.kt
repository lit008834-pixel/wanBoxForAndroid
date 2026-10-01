// @author 雾晚
package io.nekohasekai.sagernet.ui

import org.junit.Assert.*
import org.junit.Test

class UiLayoutPolicyTest {
    @Test fun manualSelectionIsRestoredAndNeverDependsOnWindowOrFontMetrics() {
        // A recreated page resolves its persisted preference, without a size-based override.
        assertEquals(2, UiLayoutPolicy.columns(true))
        assertEquals(1, UiLayoutPolicy.columns(false))
    }
    @Test fun repeatedLayoutSwitchesHonorTheLatestChoice() {
        assertEquals(listOf(2, 1, 2, 1), listOf(true, false, true, false).map(UiLayoutPolicy::columns))
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
