// @author 雾晚
package io.nekohasekai.sagernet.utils

import android.app.WallpaperManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getColorAttr

object Theme {

    const val MONET = 0
    const val RED = 1
    const val PINK_SSR = 2
    const val PINK = 3
    const val PURPLE = 4
    const val DEEP_PURPLE = 5
    const val INDIGO = 6
    const val BLUE = 7
    const val LIGHT_BLUE = 8
    const val CYAN = 9
    const val TEAL = 10
    const val GREEN = 11
    const val LIGHT_GREEN = 12
    const val LIME = 13
    const val YELLOW = 14
    const val AMBER = 15
    const val ORANGE = 16
    const val DEEP_ORANGE = 17
    const val BROWN = 18
    const val GREY = 19
    const val BLUE_GREY = 20
    const val BLACK = 21
    const val VERDANT_MINT = 22
    const val WHITE = 23
    const val LIGHT_GRAY = 24
    const val CUSTOM = 99

    fun isSupportedTheme(theme: Int): Boolean = theme in MONET..LIGHT_GRAY || theme == CUSTOM

    fun customPrimaryColor(): Int {
        val stored = DataStore.customThemeColor
        // Older versions stored RGB without an alpha byte.
        return if (Color.alpha(stored) == 0) stored or 0xFF000000.toInt() else stored
    }

    private fun customSeedBitmap(): Bitmap =
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply {
            eraseColor(customPrimaryColor())
        }

    fun customColorForAttribute(attribute: Int): Int? {
        if (DataStore.appTheme != CUSTOM || DataStore.useSystemTheme) return null
        val primary = customPrimaryColor()
        return when (attribute) {
            R.attr.colorPrimary, R.attr.colorAccent, R.attr.colorControlActivated,
            R.attr.fabColorBackground, R.attr.selectedColorPrimary,
            R.attr.tabSelectedTextColor, R.attr.tabIndicatorColor,
            R.attr.accentOrTextPrimary, R.attr.accentOrTextSecondary,
            R.attr.primaryOrTextPrimary, R.attr.primaryOrTextSecondary -> primary
            R.attr.colorPrimaryDark -> ColorUtils.blendARGB(primary, Color.BLACK, 0.22f)
            R.attr.tabRippleColor -> ColorUtils.setAlphaComponent(primary, 0x33)
            R.attr.colorOnPrimary -> if (ColorUtils.calculateLuminance(primary) > 0.5) Color.BLACK else Color.WHITE
            else -> null
        }
    }

    fun tintCustomViews(root: View) {
        if (DataStore.appTheme != CUSTOM || DataStore.useSystemTheme) return
        val primary = customPrimaryColor()
        val onPrimary = if (ColorUtils.calculateLuminance(primary) > 0.5) Color.BLACK else Color.WHITE
        val tint = ColorStateList.valueOf(primary)
        when (root) {
            is FloatingActionButton -> {
                root.backgroundTintList = tint
                root.imageTintList = ColorStateList.valueOf(onPrimary)
            }
            is MaterialButton -> {
                if (root.backgroundTintList?.defaultColor == Color.TRANSPARENT) {
                    root.setTextColor(primary)
                } else {
                    root.backgroundTintList = tint
                    root.setTextColor(onPrimary)
                }
            }
            is TabLayout -> {
                root.setSelectedTabIndicatorColor(primary)
                root.setTabTextColors(root.context.getColorAttr(android.R.attr.textColorSecondary), primary)
                root.tabRippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 0x33))
            }
            is NavigationView -> {
                val defaultColor = root.context.getColorAttr(android.R.attr.textColorPrimary)
                val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
                val colors = intArrayOf(primary, defaultColor)
                root.itemIconTintList = ColorStateList(states, colors)
                root.itemTextColor = ColorStateList(states, colors)
            }
            is Toolbar -> {
                root.setTitleTextColor(primary)
                root.navigationIcon?.mutate()?.let { DrawableCompat.setTint(it, primary) }
                root.overflowIcon?.mutate()?.let { DrawableCompat.setTint(it, primary) }
            }
            is TextInputLayout -> {
                root.boxStrokeColor = primary
                root.defaultHintTextColor = tint
            }
            is SwitchCompat -> {
                root.thumbTintList = tint
                root.trackTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 0x66))
            }
            is Button -> root.setTextColor(primary)
            is CompoundButton -> root.buttonTintList = tint
            is ProgressBar -> root.progressTintList = tint
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) tintCustomViews(root.getChildAt(index))
        }
    }

    fun getClosestThemeForColor(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        val sat = hsv[1]
        val value = hsv[2]

        return when {
            sat < 0.18f && value >= 0.88f -> WHITE
            sat < 0.18f && value <= 0.25f -> BLACK
            else -> LIGHT_GRAY
        }
    }

    fun getSystemWallpaperColor(context: Context): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val wallpaperManager = WallpaperManager.getInstance(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    val colors = wallpaperManager?.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
                    val primary = colors?.primaryColor?.toArgb()
                    if (primary != null && primary != 0) {
                        return primary
                    }
                }
                val sysAccent = context.getColor(android.R.color.system_accent1_600)
                if (sysAccent != 0) return sysAccent
            } catch (_: Throwable) {
            }
        }
        return null
    }

    fun apply(context: Context) {
        context.setTheme(getTheme())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && context is android.app.Activity) {
            if (DataStore.appTheme == CUSTOM && !DataStore.useSystemTheme) {
                DynamicColors.applyToActivityIfAvailable(
                    context,
                    DynamicColorsOptions.Builder().setContentBasedSource(customSeedBitmap()).build()
                )
            } else if (!isWhiteTheme() && !isLightGrayTheme() && DataStore.useSystemTheme) {
                DynamicColors.applyIfAvailable(context)
            }
        }
    }

    fun applyDialog(context: Context) {
        context.setTheme(getDialogTheme())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && context is android.app.Activity) {
            if (DataStore.appTheme == CUSTOM && !DataStore.useSystemTheme) {
                DynamicColors.applyToActivityIfAvailable(
                    context,
                    DynamicColorsOptions.Builder().setContentBasedSource(customSeedBitmap()).build()
                )
            } else if (!isWhiteTheme() && !isLightGrayTheme() && DataStore.useSystemTheme) {
                DynamicColors.applyIfAvailable(context)
            }
        }
    }

    fun getTheme(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && DataStore.useSystemTheme) {
            val wallpaperColor = getSystemWallpaperColor(app)
            if (wallpaperColor != null) {
                val closest = getClosestThemeForColor(wallpaperColor)
                if (closest == WHITE) R.style.Theme_SagerNet_White else getTheme(closest)
            } else {
                getTheme(MONET)
            }
        } else {
            getTheme(DataStore.appTheme)
        }
    }

    fun getDialogTheme(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && DataStore.useSystemTheme) {
            val wallpaperColor = getSystemWallpaperColor(app)
            if (wallpaperColor != null) {
                val closest = getClosestThemeForColor(wallpaperColor)
                if (closest == WHITE) R.style.Theme_SagerNet_Dialog_White else getDialogTheme(closest)
            } else {
                getDialogTheme(MONET)
            }
        } else {
            getDialogTheme(DataStore.appTheme)
        }
    }

    fun getTheme(theme: Int): Int {
        return when (theme) {
            MONET -> R.style.Theme_SagerNet_Monet
            RED -> R.style.Theme_SagerNet_Red
            PINK_SSR -> R.style.Theme_SagerNet_Pink_SSR
            PINK -> R.style.Theme_SagerNet_Pink
            PURPLE -> R.style.Theme_SagerNet_Purple
            DEEP_PURPLE -> R.style.Theme_SagerNet_DeepPurple
            INDIGO -> R.style.Theme_SagerNet_Indigo
            BLUE -> R.style.Theme_SagerNet_Blue
            LIGHT_BLUE -> R.style.Theme_SagerNet_LightBlue
            CYAN -> R.style.Theme_SagerNet_Cyan
            TEAL -> R.style.Theme_SagerNet_Teal
            GREEN -> R.style.Theme_SagerNet_Green
            LIGHT_GREEN -> R.style.Theme_SagerNet_LightGreen
            LIME -> R.style.Theme_SagerNet_Lime
            YELLOW -> R.style.Theme_SagerNet_Yellow
            AMBER -> R.style.Theme_SagerNet_Amber
            ORANGE -> R.style.Theme_SagerNet_Orange
            DEEP_ORANGE -> R.style.Theme_SagerNet_DeepOrange
            BROWN -> R.style.Theme_SagerNet_Brown
            GREY -> R.style.Theme_SagerNet_Grey
            BLUE_GREY -> R.style.Theme_SagerNet_BlueGrey
            BLACK -> R.style.Theme_SagerNet_Black
            VERDANT_MINT -> R.style.Theme_SagerNet_VerdantMint
            WHITE -> R.style.Theme_SagerNet_White
            LIGHT_GRAY -> R.style.Theme_SagerNet_LightGray
            CUSTOM -> R.style.Theme_SagerNet_Custom
            else -> R.style.Theme_SagerNet_LightGray
        }
    }

    fun getDialogTheme(theme: Int): Int {
        return when (theme) {
            MONET -> R.style.Theme_SagerNet_Dialog_Monet
            RED -> R.style.Theme_SagerNet_Dialog_Red
            PINK_SSR -> R.style.Theme_SagerNet_Dialog_Pink_SSR
            PINK -> R.style.Theme_SagerNet_Dialog_Pink
            PURPLE -> R.style.Theme_SagerNet_Dialog_Purple
            DEEP_PURPLE -> R.style.Theme_SagerNet_Dialog_DeepPurple
            INDIGO -> R.style.Theme_SagerNet_Dialog_Indigo
            BLUE -> R.style.Theme_SagerNet_Dialog_Blue
            LIGHT_BLUE -> R.style.Theme_SagerNet_Dialog_LightBlue
            CYAN -> R.style.Theme_SagerNet_Dialog_Cyan
            TEAL -> R.style.Theme_SagerNet_Dialog_Teal
            GREEN -> R.style.Theme_SagerNet_Dialog_Green
            LIGHT_GREEN -> R.style.Theme_SagerNet_Dialog_LightGreen
            LIME -> R.style.Theme_SagerNet_Dialog_Lime
            YELLOW -> R.style.Theme_SagerNet_Dialog_Yellow
            AMBER -> R.style.Theme_SagerNet_Dialog_Amber
            ORANGE -> R.style.Theme_SagerNet_Dialog_Orange
            DEEP_ORANGE -> R.style.Theme_SagerNet_Dialog_DeepOrange
            BROWN -> R.style.Theme_SagerNet_Dialog_Brown
            GREY -> R.style.Theme_SagerNet_Dialog_Grey
            BLUE_GREY -> R.style.Theme_SagerNet_Dialog_BlueGrey
            BLACK -> R.style.Theme_SagerNet_Dialog_Black
            VERDANT_MINT -> R.style.Theme_SagerNet_Dialog_VerdantMint
            WHITE -> R.style.Theme_SagerNet_Dialog_White
            LIGHT_GRAY -> R.style.Theme_SagerNet_Dialog_LightGray
            CUSTOM -> R.style.Theme_SagerNet_Dialog_Custom
            else -> R.style.Theme_SagerNet_Dialog_LightGray
        }
    }

    fun isWhiteTheme(): Boolean = DataStore.appTheme == WHITE
    fun isLightGrayTheme(): Boolean = DataStore.appTheme == LIGHT_GRAY
    fun isBlackTheme(): Boolean = DataStore.appTheme == BLACK

    fun getPrimaryColor(context: Context): Int {
        if (DataStore.appTheme == CUSTOM && !DataStore.useSystemTheme) return customPrimaryColor()
        if (isWhiteTheme()) {
            return Color.parseColor("#212121")
        }
        if (isLightGrayTheme()) {
            return context.getColorAttr(R.attr.colorPrimary)
        }
        if (isBlackTheme()) {
            return Color.WHITE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && DataStore.useSystemTheme) {
            val wallpaperColor = getSystemWallpaperColor(context)
            if (wallpaperColor != null) {
                return wallpaperColor
            }
        }
        return context.getColorAttr(R.attr.colorPrimary)
    }

    var currentNightMode = -1
    fun getNightMode(): Int {
        if (currentNightMode == -1) {
            currentNightMode = DataStore.nightTheme
        }
        return getNightMode(currentNightMode)
    }

    fun getNightMode(mode: Int): Int {
        return when (mode) {
            0 -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            1 -> AppCompatDelegate.MODE_NIGHT_YES
            2 -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY
        }
    }

    fun usingNightMode(): Boolean {
        if (isWhiteTheme() || isLightGrayTheme()) return false
        if (isBlackTheme()) return true
        return when (DataStore.nightTheme) {
            1 -> true
            2 -> false
            else -> (app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun applyNightTheme() {
        if (isWhiteTheme() || isLightGrayTheme()) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            return
        }
        if (isBlackTheme()) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            return
        }
        AppCompatDelegate.setDefaultNightMode(getNightMode())
    }

}
