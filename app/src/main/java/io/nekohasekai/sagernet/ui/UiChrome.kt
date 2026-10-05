// @author 雾晚
package io.nekohasekai.sagernet.ui

import android.app.ActivityManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.core.graphics.ColorUtils
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.Theme

/** Static glass on existing chrome; no list blur or captured window bitmaps. @author 雾晚 */
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

    fun palette(context: Context, informationSurface: Boolean = false): GlassPalette.Surface = GlassPalette.create(
        if (informationSurface) context.getColorAttr(R.attr.colorSurface) else surface(context),
        if (DataStore.appTheme == Theme.CUSTOM && !DataStore.useSystemTheme)
            Theme.customPrimaryColor() else context.getColorAttr(R.attr.colorPrimary),
        context.getColorAttr(android.R.attr.textColorPrimary), reduceEffects(context)
    )

    fun background(context: Context, radius: Float = 0f, informationSurface: Boolean = false): GradientDrawable {
        val colors = palette(context, informationSurface)
        return drawable(context, colors, radius)
    }

    private fun drawable(context: Context, colors: GlassPalette.Surface, radius: Float): GradientDrawable {
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(colors.top, colors.bottom)).apply {
            cornerRadius = radius
            setStroke(context.resources.getDimensionPixelSize(R.dimen.card_stroke_width), colors.edge)
        }
    }

    fun apply(view: View) { view.background = background(view.context) }

    fun applyInformationCard(content: View) {
        // MaterialCardView retains its shape, elevation, stroke and click/ripple handling.
        val colors = palette(content.context, informationSurface = true)
        content.background = drawable(content.context, colors, content.resources.getDimension(R.dimen.card_corner_radius))
        listOf(R.id.tv_subscription_title, R.id.tv_traffic_stat).forEach { id ->
            content.findViewById<android.widget.TextView>(id)?.setTextColor(text(content.context, false, colors))
        }
        listOf(R.id.tv_expire_date, R.id.tv_traffic_remaining, R.id.tv_node_count, R.id.tv_last_updated)
            .forEach { id -> content.findViewById<android.widget.TextView>(id)?.setTextColor(text(content.context, true, colors)) }
    }

    fun text(context: Context, secondary: Boolean): Int = text(context, secondary, palette(context))

    private fun text(context: Context, secondary: Boolean, colors: GlassPalette.Surface): Int {
        val attr = if (secondary) android.R.attr.textColorSecondary else android.R.attr.textColorPrimary
        val candidate = ColorUtils.compositeColors(context.getColorAttr(attr), colors.top or Color.BLACK)
        return GlassPalette.text(candidate, colors)
    }
}
