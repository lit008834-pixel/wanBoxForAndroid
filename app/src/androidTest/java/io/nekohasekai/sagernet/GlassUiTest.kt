// @author 雾晚
package io.nekohasekai.sagernet

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.appbar.MaterialToolbar
import io.nekohasekai.sagernet.ui.GlassPalette
import io.nekohasekai.sagernet.ui.UiChrome
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Real Android XML renderings; synthetic public fixtures, never user configurations. @author 雾晚 */
class GlassUiTest {
    @Test fun nativeRenderingsPreserveGeometryTextAndClickHandlers() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val previousScale = Settings.Global.getFloat(instrumentation.targetContext.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        fun animatorScale(value: Float) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                "settings put global animator_duration_scale $value")).use { it.readBytes() }
        }
        // CI normally disables animations; restore the original setting even on assertion failure.
        animatorScale(1f)
        try { instrumentation.runOnMainSync {
            val base = instrumentation.targetContext
            val output = File(base.getExternalFilesDir(null), "liquid-glass").apply { mkdirs() }
            for ((theme, style) in listOf("light" to R.style.Theme_SagerNet,
                "night" to R.style.Theme_SagerNet, "black" to R.style.Theme_SagerNet_Black)) {
                for (scale in listOf(1f, 1.3f, 2f)) for (widthDp in listOf(320, 412)) for (density in listOf(160, 320)) {
                    val config = Configuration(base.resources.configuration).apply {
                        fontScale = scale; densityDpi = density
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            if (theme == "light") Configuration.UI_MODE_NIGHT_NO else Configuration.UI_MODE_NIGHT_YES
                    }
                    val context = ContextThemeWrapper(base.createConfigurationContext(config), style)
                    val inflater = LayoutInflater.from(context)
                    val column = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(0xFF8D489F.toInt(), 0xFF007C91.toInt()))
                    }
                    val toolbar = MaterialToolbar(context).apply {
                        title = "wanBox"
                        setTitleTextColor(UiChrome.text(context, false))
                        setBackgroundColor(UiChrome.tint(context).defaultColor)
                    }
                    column.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
                    val list = inflater.inflate(R.layout.layout_profile_list, column, false)
                    val card = list.findViewById<View>(R.id.card_subscription_info)
                    (card.parent as ViewGroup).removeView(card)
                    card.visibility = View.VISIBLE
                    column.addView(card)
                    val content = card.findViewById<View>(R.id.subscription_glass_surface)
                    val values = mapOf(R.id.tv_subscription_title to "长订阅名称 / Example subscription",
                        R.id.tv_expire_date to "2026-12-31", R.id.tv_traffic_stat to "91.88 GB",
                        R.id.tv_traffic_remaining to "8.12 GB", R.id.tv_node_count to "48",
                        R.id.tv_last_updated to "2026-10-05")
                    values.forEach { (id, value) -> card.findViewById<TextView>(id).text = value }
                    var clicked = 0
                    card.setOnClickListener { clicked++ }
                    card.contentDescription = "Example subscription"
                    val node = inflater.inflate(R.layout.layout_profile, column, false)
                    node.findViewById<TextView>(R.id.profile_name).text = "日本 / Example node 01"
                    node.findViewById<TextView>(R.id.profile_type).text = "VLESS"
                    node.findViewById<TextView>(R.id.profile_status).apply { text = "72ms"; visibility = View.VISIBLE }
                    column.addView(node)
                    val width = (widthDp * context.resources.displayMetrics.density).toInt()
                    fun render(name: String): Int {
                        column.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                        column.layout(0, 0, column.measuredWidth, column.measuredHeight)
                        val bitmap = Bitmap.createBitmap(width, column.measuredHeight, Bitmap.Config.ARGB_8888)
                        try {
                            column.draw(Canvas(bitmap))
                            File(output, "${theme}_${scale}_${widthDp}_${density}_$name.png").outputStream().use {
                                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                            }
                        } finally { bitmap.recycle() }
                        return column.measuredHeight
                    }
                    val padding = listOf(content.paddingLeft, content.paddingTop, content.paddingRight, content.paddingBottom)
                    val oldHeight = render("before")
                    UiChrome.apply(toolbar); UiChrome.applyInformationCard(content)
                    assertEquals(oldHeight, render("after"))
                    assertEquals(padding, listOf(content.paddingLeft, content.paddingTop, content.paddingRight, content.paddingBottom))
                    values.forEach { (id, value) ->
                        val text = card.findViewById<TextView>(id)
                        assertEquals(value, text.text.toString())
                        assertTrue(text.height >= text.layout.height + text.compoundPaddingTop + text.compoundPaddingBottom)
                        assertTrue(GlassPalette.contrast(text.currentTextColor,
                            UiChrome.palette(context, informationSurface = true)) >= 4.5)
                    }
                    card.performClick(); assertEquals(1, clicked)
                    assertEquals("Example subscription", card.contentDescription)
                }
            }
        } } finally { animatorScale(previousScale) }
    }

    @Test fun themedMaterialsUseIndependentDrawablesAndAccessibleText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val styles = mutableListOf(R.style.Theme_SagerNet, R.style.Theme_SagerNet_Black,
                R.style.Theme_SagerNet_White, R.style.Theme_SagerNet_LightGray, R.style.Theme_SagerNet_Red)
            if (Build.VERSION.SDK_INT >= 31) styles.add(R.style.Theme_SagerNet_Monet)
            for (style in styles) {
                val context = ContextThemeWrapper(instrumentation.targetContext, style)
                assertNotSame(UiChrome.background(context), UiChrome.background(context))
                for (secondary in listOf(false, true)) assertTrue(
                    GlassPalette.contrast(UiChrome.text(context, secondary), UiChrome.palette(context)) >= 4.5)
                val radius = context.resources.getDimension(R.dimen.dialog_corner_radius)
                assertEquals(radius, UiChrome.background(context, radius).cornerRadius, 0f)
            }
        }
    }
}
