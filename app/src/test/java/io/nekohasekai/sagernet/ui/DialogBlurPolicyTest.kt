// @author 雾晚
package io.nekohasekai.sagernet.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

/** Platform capability and optional settings contract without a GPU dependency. @author 雾晚 */
class DialogBlurPolicyTest {
    @Test fun unsupportedDisabledReducedAndZeroKeepNativeAppearance() {
        for (api in listOf(21, 23, 29, 30)) assertEquals(0,
            DialogBlurPolicy.radiusPx(api, true, 25, true, false, 3f))
        assertEquals(0, DialogBlurPolicy.radiusPx(35, false, 12, true, false, 3f))
        assertEquals(0, DialogBlurPolicy.radiusPx(35, true, 12, false, false, 3f))
        assertEquals(0, DialogBlurPolicy.radiusPx(35, true, 12, true, true, 3f))
        assertEquals(0, DialogBlurPolicy.radiusPx(35, true, 0, true, false, 3f))
    }

    @Test fun strengthAndDensityAreBoundedForEverySupportedApi() {
        for (api in 31..35) {
            assertEquals(36, DialogBlurPolicy.radiusPx(api, true, 12, true, false, 3f))
            assertEquals(75, DialogBlurPolicy.radiusPx(api, true, 999, true, false, 3f))
            assertEquals(100, DialogBlurPolicy.radiusPx(api, true, 25, true, false, 10f))
            assertEquals(0, DialogBlurPolicy.radiusPx(api, true, -1, true, false, 3f))
            for (density in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY))
                assertEquals(0, DialogBlurPolicy.radiusPx(api, true, 25, true, false, density))
        }
    }

    @Test fun settingsAreOptInAndLifecycleOwnsWindowListener() {
        val ns = "http://schemas.android.com/apk/res-auto"
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val nodes = factory.newDocumentBuilder().parse(File("src/main/res/xml/global_preferences.xml"))
            .getElementsByTagName("*")
        val keyed = (0 until nodes.length).map { nodes.item(it) as Element }
            .associateBy { it.getAttributeNS(ns, "key") }
        assertEquals("false", keyed.getValue("dialogBlurEnabled").getAttributeNS(ns, "defaultValue"))
        val strength = keyed.getValue("dialogBlurStrength")
        assertEquals("dialogBlurEnabled", strength.getAttributeNS(ns, "dependency"))
        assertEquals("12", strength.getAttributeNS(ns, "defaultValue"))
        assertEquals("0", strength.getAttributeNS(ns, "min"))
        assertEquals("25", strength.getAttributeNS("http://schemas.android.com/apk/res/android", "max"))
        val code = File("src/main/java/io/nekohasekai/sagernet/ui/DialogBlur.kt").readText()
        assertTrue(code.contains("removeCrossWindowBlurEnabledListener(callback)"))
        assertTrue(code.contains("onViewDetachedFromWindow"))
        assertFalse(code.contains("setRenderEffect"))
        assertFalse(code.contains("PixelCopy"))
        assertFalse(code.contains("setOnDismissListener")) // Existing dismiss handlers remain owned by callers.
    }
}
