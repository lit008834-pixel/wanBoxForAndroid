// @author 雾晚
package io.nekohasekai.sagernet

import android.content.ComponentName
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Loads and renders the installed icon in debug and R8 release, without launcher fallbacks. @author 雾晚 */
class LauncherIconTest {
    @Test fun installedLauncherUsesOriginalArtworkInBothThemes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageManager = context.packageManager
        val artId = context.resources.getIdentifier("wanbox_launcher_art", "drawable", context.packageName)
        assertTrue("Launcher foreground missing from packaged resources", artId != 0)
        val original = BitmapFactory.decodeResource(context.resources, artId)
        assertNotNull("Launcher foreground is not a decodable bitmap", original)
        try {
            val launcher = packageManager.getActivityInfo(
                ComponentName(context.packageName, "io.nekohasekai.sagernet.launcher.NekoBoxPlus"), 0
            )
            for (mode in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                val config = Configuration(context.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
                }
                val themed = context.createConfigurationContext(config)
                val icons = listOf(
                    themed.resources.getDrawable(launcher.icon, themed.theme),
                    launcher.loadIcon(packageManager),
                    context.applicationInfo.loadIcon(packageManager)
                )
                for (icon in icons) {
                    assertTrue("Packaged launcher did not load its adaptive icon", icon is AdaptiveIconDrawable)
                    val foreground = (icon as AdaptiveIconDrawable).foreground
                    assertTrue("Adaptive foreground must resolve to artwork, not another icon", foreground is BitmapDrawable)
                    assertTrue("Launcher artwork changed", (foreground as BitmapDrawable).bitmap.sameAs(original))
                    val rendered = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
                    try {
                        icon.setBounds(0, 0, 128, 128)
                        icon.draw(Canvas(rendered))
                        val pixels = IntArray(128 * 128)
                        rendered.getPixels(pixels, 0, 128, 0, 0, 128, 128)
                        assertTrue("Launcher rendered blank", pixels.toSet().size > 8)
                    } finally { rendered.recycle() }
                }
            }
        } finally { original?.recycle() }
    }
}
