// @author 雾晚
package io.nekohasekai.sagernet

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Resource-level guards supplement device layout tests; these do not claim visual device QA. */
class UiLayoutResourcesTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"
    private fun layout(name: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(File("src/main/res/layout/$name.xml"))
    private fun element(name: String, id: String): Element {
        val nodes = layout(name).getElementsByTagName("*")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .first { it.getAttributeNS(androidNs, "id") == "@+id/$id" }
    }
    @Test fun nodeNameAndMultilineResultsAreNotTruncated() {
        for (id in listOf("profile_name", "profile_status")) {
            val node = element("layout_profile", id)
            assertNotEquals("true", node.getAttributeNS(androidNs, "singleLine"))
            assertEquals("", node.getAttributeNS(androidNs, "ellipsize"))
            assertEquals("", node.getAttributeNS(androidNs, "maxLines"))
        }
    }
    @Test fun refreshAndOverflowHaveMinimumTargetsAndAccessibleNames() {
        for ((name, id) in listOf("layout_main" to "btn_ip_detail", "layout_profile" to "double_column_menu", "layout_group_item" to "options")) {
            val node = element(name, id)
            assertEquals("@dimen/ui_touch_target", node.getAttributeNS(androidNs, "layout_width"))
            assertEquals("@dimen/ui_touch_target", node.getAttributeNS(androidNs, "layout_height"))
            assertTrue(node.getAttributeNS(androidNs, "contentDescription").startsWith("@string/"))
        }
    }
    @Test fun statsTextIsNotShrunkAndSettingsGetRemainingViewport() {
        val stats = File("src/main/res/layout/layout_main.xml").readText()
        assertFalse(stats.contains("autoSizeMinTextSize"))
        assertEquals("polite", element("layout_main", "status").getAttributeNS(androidNs, "accessibilityLiveRegion"))
        val settings = element("layout_config_settings", "settings")
        assertEquals("0dp", settings.getAttributeNS(androidNs, "layout_height"))
        assertEquals("1", settings.getAttributeNS(androidNs, "layout_weight"))
    }
    @Test fun backupUsesPrimaryExportAndSecondaryImportWithoutChangingActions() {
        assertEquals("@style/Widget.WanBox.Ui.PrimaryButton", element("layout_backup", "action_export").getAttribute("style"))
        assertEquals("@style/Widget.WanBox.Ui.SecondaryButton", element("layout_backup", "action_import_file").getAttribute("style"))
        assertEquals("@style/Widget.WanBox.Ui.SecondaryButton", element("layout_backup", "restore_from_webdav").getAttribute("style"))
    }
}
