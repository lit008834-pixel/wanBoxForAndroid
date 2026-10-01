// @author 雾晚
package io.nekohasekai.sagernet.ui

import android.app.ActivityManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.core.graphics.ColorUtils
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.Theme

/** Lightweight translucent chrome only; never blur the list, text or capture window bitmaps. */
internal object UiChrome {
    fun surface(context: Context): Int = when {
        Theme.isWhiteTheme() -> Color.WHITE
        Theme.isLightGrayTheme() -> context.getColorAttr(android.R.attr.colorBackground)
        Theme.isBlackTheme() -> Color.BLACK
        else -> context.getColorAttr(R.attr.colorSurface)
    }

    private fun reduceEffects(context: Context): Boolean {
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val accessibility = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val animationsDisabled = Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f
        return activity?.isLowRamDevice == true || power?.isPowerSaveMode == true ||
            accessibility?.isTouchExplorationEnabled == true || animationsDisabled
    }

    fun tint(context: Context): ColorStateList = ColorStateList.valueOf(
        ColorUtils.setAlphaComponent(surface(context), UiLayoutPolicy.chromeAlpha(reduceEffects(context)))
    )

    fun apply(view: View) { view.setBackgroundColor(tint(view.context).defaultColor) }

    fun text(context: Context, secondary: Boolean): Int {
        val background = surface(context)
        val attr = if (secondary) android.R.attr.textColorSecondary else android.R.attr.textColorPrimary
        val candidate = ColorUtils.compositeColors(context.getColorAttr(attr), background)
        return if (ColorUtils.calculateContrast(candidate, background) >= 5.0) candidate
        else if (ColorUtils.calculateContrast(Color.BLACK, background) >= 4.5) Color.BLACK
        else Color.WHITE
    }
}
