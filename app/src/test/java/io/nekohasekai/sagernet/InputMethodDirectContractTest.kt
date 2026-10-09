// @author 雾晚
package io.nekohasekai.sagernet

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Guards the requested UI boundary and routing reload ownership, not voice connectivity. @author 雾晚 */
class InputMethodDirectContractTest {
    @Test fun keyboardHasNoSeparateSwitchAndRoutingChangesRebuildService() {
        val ui = File("src/main/java/io/nekohasekai/sagernet/ui/SettingsPreferenceFragment.kt").readText()
        assertFalse(ui.contains("INPUT_METHOD_DIRECT"))
        val xml = File("src/main/res/xml/global_preferences.xml").readText()
        assertFalse(xml.contains("app:key=\"inputMethodDirect\""))
        for (file in listOf("RouteFragment.kt", "RouteSettingsActivity.kt")) {
            val routing = File("src/main/java/io/nekohasekai/sagernet/ui/$file").readText()
            assertTrue(routing.contains("SagerNet.restartService()"))
            // Both aliases now rebuild a full snapshot through the same bounded queue.
            val application = File("src/main/java/io/nekohasekai/sagernet/SagerNet.kt").readText()
            assertTrue(application.contains("fun reloadService() { configurationReload.request() }"))
            assertTrue(application.contains("fun restartService() { configurationReload.request() }"))
        }
    }
}
