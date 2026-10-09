// @author 雾晚
package io.nekohasekai.sagernet

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Direct shortcuts stay private; authorization and node selection retain their paths. @author 雾晚 */
class ShortcutControlContractTest {
    @Test fun directControlsArePrivateAndNoLongerWaitForAppConsent() {
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val activities = manifest.getElementsByTagName("activity")
        for (name in listOf("QuickToggleShortcut", "QuickEnableShortcut", "QuickDisableShortcut")) {
            val activity = (0 until activities.length).map { activities.item(it) as org.w3c.dom.Element }
                .single { it.getAttributeNS("http://schemas.android.com/apk/res/android", "name").endsWith(".$name") }
            assertEquals("false", activity.getAttributeNS("http://schemas.android.com/apk/res/android", "exported"))
            val text = File("src/main/java").walkTopDown().single { it.name == "$name.kt" }.readText()
            assertFalse(text.contains("AlertDialog")); assertFalse(text.contains("confirmControl"))
            assertTrue(text.substringAfter("override fun onCreate").substringBefore("override fun onServiceConnected")
                .contains("connection.connect(this, this)"))
            assertTrue(text.contains("connection.disconnect(this)"))
        }
    }

    @Test fun toggleKeepsNodeValidationAndServiceSemanticsAndSystemAuthorization() {
        val toggle = File("src/main/java/io/nekohasekai/sagernet/QuickToggleShortcut.kt").readText()
        assertTrue(toggle.contains("ProfileManager.getProfile(profileId) == null"))
        assertTrue(toggle.contains("SagerNet.toggleService(profileId)"))
        val commands = File("src/main/java/io/nekohasekai/sagernet/SagerNet.kt").readText()
        assertTrue(commands.contains("val status = client.call(\"status\")"))
        assertTrue(commands.contains("profileId == status.profileId"))
        assertTrue(commands.contains("DataStore.selectedProxy = profileId"))
        assertFalse(File("src/main/java/io/nekohasekai/sagernet/ui/VpnRequestActivity.kt").exists())
        val core = File("src/main/java/io/nekohasekai/sagernet/SagerNet.kt").readText()
        assertTrue(core.contains("RootModuleClient.startOrReload()"))
        assertTrue(File("src/main/java/io/nekohasekai/sagernet/ui/SwitchActivity.kt").readText().contains("SagerNet.startService()"))
        assertTrue(core.contains("RootModuleClient.stop()"))
        val shortcuts = File("src/main/res/xml/shortcuts.xml").readText()
        assertTrue(shortcuts.contains("QuickToggleShortcut")); assertTrue(shortcuts.contains("QuickEnableShortcut"))
        assertTrue(shortcuts.contains("QuickDisableShortcut"))
    }
}
