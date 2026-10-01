// @author 雾晚
package io.nekohasekai.sagernet.ui

/** Pure sizing policy: preserve the user's grid preference without forcing tiny cards/text. */
internal object UiLayoutPolicy {
    fun columns(gridRequested: Boolean, availableWidthDp: Float, fontScale: Float): Int {
        if (!gridRequested || !availableWidthDp.isFinite() || !fontScale.isFinite()) return 1
        val minimumCardWidth = 168f * fontScale.coerceAtLeast(1f)
        return if (availableWidthDp >= minimumCardWidth * 2f) 2 else 1
    }

    fun bottomPadding(minimum: Int, barHeight: Int, clearance: Int, bottomInset: Int): Int =
        maxOf(minimum, barHeight + clearance) + bottomInset

    fun chromeAlpha(reduceEffects: Boolean): Int = if (reduceEffects) 255 else 245
}
