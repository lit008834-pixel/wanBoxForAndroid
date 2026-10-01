// @author 雾晚
package io.nekohasekai.sagernet.ui

/** @author 雾晚: Explicit column choices must survive resizing and font changes. */
internal object UiLayoutPolicy {
    fun columns(gridRequested: Boolean): Int = if (gridRequested) 2 else 1

    fun bottomPadding(minimum: Int, barHeight: Int, clearance: Int, bottomInset: Int): Int =
        maxOf(minimum, barHeight + clearance) + bottomInset

    fun chromeAlpha(reduceEffects: Boolean): Int = if (reduceEffects) 255 else 245
}
