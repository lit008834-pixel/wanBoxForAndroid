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
import io.nekohasekai.sagernet.ui.ProfileCardStyle
import io.nekohasekai.sagernet.ui.UiLayoutPolicy
import android.text.TextUtils
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class UiLayoutAccessibilityTest {
    @Test fun compactCardsStayTwoColumnsAndRestoreSingleColumnOnReuse() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (locale in listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE)) {
                for (night in listOf(false, true)) for (scale in listOf(1f, 2f)) {
                    val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
                        setLocale(locale); fontScale = scale
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    }
                    val themed = ContextThemeWrapper(instrumentation.targetContext.createConfigurationContext(config),
                        if (night) R.style.Theme_SagerNet_Black else R.style.Theme_SagerNet_White)
                    for (widthDp in listOf(152, 192, 320)) {
                        val view = LayoutInflater.from(themed).inflate(R.layout.layout_profile, null)
                        val name = view.findViewById<TextView>(R.id.profile_name)
                        val status = view.findViewById<TextView>(R.id.profile_status)
                        val address = view.findViewById<TextView>(R.id.profile_address)
                        val type = view.findViewById<TextView>(R.id.profile_type)
                        val menu = view.findViewById<View>(R.id.double_column_menu).apply { visibility = View.VISIBLE }
                        name.text = "日本 / Singapore long profile name with recognizable suffix 012345"
                        name.contentDescription = name.text
                        address.text = "[2001:db8:abcd:1234:5678:90ab:cdef:1234]:443"
                        (address.parent as View).visibility = View.VISIBLE
                        type.text = "VLESS"
                        val style = ProfileCardStyle(view)
                        fun measure(): Int {
                            val width = (widthDp * themed.resources.displayMetrics.density).toInt()
                            repeat(3) {
                                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                            }
                            return view.measuredHeight
                        }
                        for (result in listOf("", "75ms", "75ms\n↓ 42.5 Mbps ↑ 20.1 Mbps", "Connection failed: long diagnostic with full details")) {
                            status.text = result
                            style.apply(false)
                            val expanded = measure()
                            style.apply(true)
                            val compact = measure()
                            assertEquals(2, UiLayoutPolicy.columns(true))
                            assertEquals(1, name.maxLines)
                            assertEquals(TextUtils.TruncateAt.MIDDLE, name.ellipsize)
                            assertEquals(name.text, name.contentDescription)
                            assertEquals(1, address.maxLines)
                            assertEquals(2, status.maxLines)
                            assertEquals(result, status.text.toString())
                            assertTrue("Compact card must reduce height", compact < expanded)
                            assertTrue(menu.width >= themed.resources.getDimensionPixelSize(R.dimen.ui_touch_target))
                            assertTrue(menu.height >= themed.resources.getDimensionPixelSize(R.dimen.ui_touch_target))
                            style.apply(false); measure()
                            assertEquals(Int.MAX_VALUE, name.maxLines)
                            assertNull(name.ellipsize)
                            assertEquals(Int.MAX_VALUE, address.maxLines)
                            assertEquals(Int.MAX_VALUE, status.maxLines)
                            assertEquals(LinearLayout.VERTICAL, view.findViewById<LinearLayout>(R.id.profile_title_area).orientation)
                            assertEquals(LinearLayout.VERTICAL, view.findViewById<LinearLayout>(R.id.profile_status_area).orientation)
                            assertEquals(expanded, view.measuredHeight)
                        }
                    }
                }
            }
        }
    }
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
