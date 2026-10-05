// @author 雾晚
package io.nekohasekai.sagernet

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Guards opt-in persistence and restart ownership, not device behavior. @author 雾晚 */
class InputMethodDirectContractTest {
    @Test fun preferenceIsOptInAndPersistedChangeRebuildsService() {
        val store = File("src/main/java/io/nekohasekai/sagernet/database/DataStore.kt").readText()
        assertTrue(store.contains("boolean(Key.INPUT_METHOD_DIRECT) { false }"))
        val ui = File("src/main/java/io/nekohasekai/sagernet/ui/SettingsPreferenceFragment.kt").readText()
        assertTrue(ui.contains("key == Key.INPUT_METHOD_DIRECT) && DataStore.serviceState.started"))
        assertTrue(ui.contains("SagerNet.restartService()"))
        val xml = File("src/main/res/xml/global_preferences.xml").readText()
        assertTrue(xml.contains("app:key=\"inputMethodDirect\""))
        for (locale in listOf("values", "values-zh-rCN")) {
            val strings = File("src/main/res/$locale/strings.xml").readText()
            assertTrue(strings.contains("name=\"input_method_direct_summary\""))
            assertTrue(strings.contains("name=\"input_method_direct_unavailable\""))
        }
    }
}
