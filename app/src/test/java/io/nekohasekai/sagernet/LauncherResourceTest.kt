// @author 雾晚
package io.nekohasekai.sagernet

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Checks every resource variant reachable from the launcher, including values aliases. @author 雾晚 */
class LauncherResourceTest {
    @Test fun launcherDrawableGraphHasNoCyclesAcrossQualifiers() {
        val edges = mutableMapOf<String, MutableSet<String>>()
        val resources = File("src/main/res")
        val reference = Regex("@(drawable|mipmap)/[a-z0-9_]+")
        val xml = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder()
        resources.walkTopDown().filter { it.isFile }.forEach { file ->
            val type = file.parentFile!!.name.substringBefore('-')
            if (type == "drawable" || type == "mipmap") {
                val key = "$type/${file.nameWithoutExtension}"
                val targets = edges.getOrPut(key) { mutableSetOf() }
                if (file.extension == "xml") reference.findAll(file.readText()).forEach {
                    targets += it.value.removePrefix("@")
                }
            } else if (type == "values" && file.extension == "xml") {
                val items = xml.parse(file).getElementsByTagName("item")
                for (index in 0 until items.length) {
                    val item = items.item(index) as org.w3c.dom.Element
                    val aliasType = item.getAttribute("type")
                    if (aliasType == "drawable" || aliasType == "mipmap") {
                        val targets = edges.getOrPut("$aliasType/${item.getAttribute("name")}") { mutableSetOf() }
                        reference.findAll(item.textContent).forEach { targets += it.value.removePrefix("@") }
                    }
                }
            }
        }
        fun visit(key: String, stack: Set<String>) {
            assertTrue("Cyclic launcher resource: ${stack.joinToString(" -> ")} -> $key", key !in stack)
            assertTrue("Missing launcher resource: $key", key in edges)
            edges.getValue(key).forEach { visit(it, stack + key) }
        }
        visit("mipmap/wanbox_launcher", emptySet())
        assertTrue(File(resources, "drawable-nodpi/wanbox_launcher_art.png").length() > 0)
    }
}
