// @author 雾晚
package io.nekohasekai.sagernet

import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.graphics.ColorUtils
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.ui.UiChrome
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class UiLayoutAccessibilityTest {
    @Test fun profileTextFitsWithLargeFontsChineseEnglishAndLightDarkThemes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (locale in listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE)) {
                for (night in listOf(false, true)) for (scale in listOf(1f, 2f)) {
                    val configuration = Configuration(instrumentation.targetContext.resources.configuration).apply {
                        setLocale(locale)
                        fontScale = scale
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    }
                    val themed = ContextThemeWrapper(
                        instrumentation.targetContext.createConfigurationContext(configuration),
                        if (night) R.style.Theme_SagerNet_Black else R.style.Theme_SagerNet_White
                    )
                    val view = LayoutInflater.from(themed).inflate(R.layout.layout_profile, null)
                    val name = view.findViewById<TextView>(R.id.profile_name)
                    val status = view.findViewById<TextView>(R.id.profile_status)
                    name.text = "Long profile name / 日本电信节点 01 — independent layout check"
                    status.text = "HTTPS 123ms\nDownload 42.5 MB/s\nConnection unavailable"
                    val menu = view.findViewById<ImageView>(R.id.double_column_menu)
                    menu.visibility = View.VISIBLE
                    val width = (320 * themed.resources.displayMetrics.density).toInt()
                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                    for (text in listOf(name, status)) {
                        assertTrue(text.layout.lineCount > 1)
                        assertTrue(text.height >= text.layout.height + text.compoundPaddingTop + text.compoundPaddingBottom)
                        for (line in 0 until text.layout.lineCount) assertEquals(0, text.layout.getEllipsisCount(line))
                    }
                    val minimum = themed.resources.getDimensionPixelSize(R.dimen.ui_touch_target)
                    assertTrue(menu.width >= minimum && menu.height >= minimum)
                    for (secondary in listOf(false, true)) assertTrue(
                        ColorUtils.calculateContrast(UiChrome.text(themed, secondary), UiChrome.surface(themed)) >= 4.5
                    )
                }
            }
        }
    }
}
