// @author 雾晚
package io.nekohasekai.sagernet.ui

import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.Theme

abstract class ThemedActivity : AppCompatActivity {
    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    var themeResId = 0
    var uiMode = 0
    open val isDialog = false
    private var lastUseSystemTheme: Boolean = false
    private var lastWallpaperColor: Int? = null
    private var lastAppTheme: Int = 0
    private var lastCustomThemeColor: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        if (io.nekohasekai.sagernet.SagerNet.databaseFailure != null) {
            setTheme(R.style.Theme_SagerNet_LightGray)
            super.onCreate(null)
            return
        }
        lastUseSystemTheme = DataStore.useSystemTheme
        lastWallpaperColor = if (DataStore.useSystemTheme) Theme.getSystemWallpaperColor(this) else null
        lastAppTheme = DataStore.appTheme
        lastCustomThemeColor = DataStore.customThemeColor

        if (!isDialog) {
            Theme.apply(this)
        } else {
            Theme.applyDialog(this)
        }
        Theme.applyNightTheme()

        super.onCreate(savedInstanceState)

        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: FragmentManager, fragment: Fragment, view: View, savedInstanceState: Bundle?
                ) {
                    Theme.tintCustomViews(view)
                }

                override fun onFragmentStarted(fm: FragmentManager, fragment: Fragment) {
                    (fragment as? DialogFragment)?.dialog?.let(DialogBlur::install)
                }
            }, true
        )

        uiMode = resources.configuration.uiMode

        window.statusBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            WindowCompat.setDecorFitsSystemWindows(window, false)

            val insetController = WindowCompat.getInsetsController(window, window.decorView)
            val surfaceColor = getColorAttr(R.attr.colorSurface)
            val isLightSurface = ColorUtils.calculateLuminance(surfaceColor) > 0.45

            insetController.isAppearanceLightStatusBars = isLightSurface
            insetController.isAppearanceLightNavigationBars = isLightSurface
        }

        applyAppBarInsets()
    }

    fun applyAppBarInsets() {
        val content = findViewById<View>(android.R.id.content) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            findViewById<AppBarLayout>(R.id.appbar)?.apply {
                updatePadding(top = bars.top)
            }
            insets
        }
        ViewCompat.requestApplyInsets(content)
    }

    override fun setContentView(view: View?) {
        super.setContentView(view)
        view?.let(Theme::tintCustomViews)
        applyAppBarInsets()
    }

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        findViewById<View>(android.R.id.content)?.let(Theme::tintCustomViews)
        applyAppBarInsets()
    }

    override fun setContentView(view: View?, params: ViewGroup.LayoutParams?) {
        super.setContentView(view, params)
        view?.let(Theme::tintCustomViews)
        applyAppBarInsets()
    }

    override fun setTheme(resId: Int) {
        super.setTheme(resId)

        themeResId = resId
    }

    override fun onResume() {
        super.onResume()
        if (io.nekohasekai.sagernet.SagerNet.databaseFailure != null) return
        val currentWallpaperColor = if (DataStore.useSystemTheme) Theme.getSystemWallpaperColor(this) else null
        if (lastUseSystemTheme != DataStore.useSystemTheme ||
            (DataStore.useSystemTheme && lastWallpaperColor != currentWallpaperColor) ||
            (!DataStore.useSystemTheme && (lastAppTheme != DataStore.appTheme ||
                (DataStore.appTheme == Theme.CUSTOM &&
                    lastCustomThemeColor != DataStore.customThemeColor)))) {
            ActivityCompat.recreate(this)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (newConfig.uiMode != uiMode) {
            uiMode = newConfig.uiMode
            ActivityCompat.recreate(this)
        }
    }

    fun snackbar(@StringRes resId: Int): Snackbar = snackbar("").setText(resId)
    fun snackbar(text: CharSequence): Snackbar = snackbarInternal(text).apply {
        view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text).apply {
            maxLines = 10
        }
    }

    internal open fun snackbarInternal(text: CharSequence): Snackbar = throw NotImplementedError()

}
