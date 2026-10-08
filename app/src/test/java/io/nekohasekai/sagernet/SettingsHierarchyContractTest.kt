// @author 雾晚
package io.nekohasekai.sagernet

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

/** Snapshot covers every legacy key/type/default/dependency, independently of UI ordering. @author 雾晚 */
class SettingsHierarchyContractTest {
    @Test fun everyOldKeyKeepsItsValueContractAndControlType() {
        val old = JSONObject(javaClass.getResource("/settings-before-3.0.7.json")!!.readText())
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(File("src/main/res/xml/global_preferences.xml"))
        val elements = document.getElementsByTagName("*")
        val actual = mutableMapOf<String, Element>()
        for (i in 0 until elements.length) {
            val element = elements.item(i) as Element
            val key = element.getAttributeNS("http://schemas.android.com/apk/res-auto", "key")
            if (key.isNotEmpty()) { assertNull("Duplicate key $key", actual.put(key, element)) }
        }
        // @author 雾晚: input-method routing is an ordinary rule, not a separate preference.
        assertEquals(old.keySet(), actual.keys)
        old.keys().forEach { key ->
            val before = old.getJSONObject(key); val node = actual.getValue(key)
            if (key == "serviceMode") {
                assertEquals("Preference", node.tagName)
                assertEquals("false", node.getAttributeNS("http://schemas.android.com/apk/res-auto", "selectable"))
                return@forEach
            }
            assertEquals(key, before.getString("type"), node.tagName)
            val attributes = before.getJSONObject("attributes")
            attributes.keys().forEach { attribute ->
                val name = attribute.substringAfter('}')
                if (name !in setOf("title", "summary")) {
                    assertEquals("$key/$name", attributes.getString(attribute),
                        node.getAttributeNS(attribute.substringAfter('{').substringBefore('}'), name))
                }
            }
        }
        assertEquals("categoryCore", (actual.getValue("categoryFragment").parentNode as Element)
            .getAttributeNS("http://schemas.android.com/apk/res-auto", "key"))
    }
}
