// @author 雾晚
package io.nekohasekai.sagernet.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** @author 雾晚 */
class DocSearchTest {
    @Test fun addedTopicsAreLocalizedUniqueAndLinkedToCards() {
        val files = listOf("values", "values-zh-rCN").map { locale ->
            sequenceOf(File("src/main/res/$locale/strings.xml"), File("app/src/main/res/$locale/strings.xml"))
                .first { it.isFile }
        }
        val catalogs = files.map { file ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            (0 until nodes.length).map { nodes.item(it) }.filter {
                it.attributes.getNamedItem("name").nodeValue.startsWith("docs_")
            }.associate { it.attributes.getNamedItem("name").nodeValue to it.textContent }
        }
        assertEquals(catalogs[0].keys, catalogs[1].keys)
        for (catalog in catalogs) {
            val titles = catalog.filterKeys { it.endsWith("_title") }.values
            assertEquals(8, titles.size)
            assertEquals(titles.size, titles.toSet().size)
            assertTrue(catalog.values.all { it.isNotBlank() })
        }
        val source = sequenceOf(File("src/main/java/io/nekohasekai/sagernet/ui/DocsFragment.kt"),
            File("app/src/main/java/io/nekohasekai/sagernet/ui/DocsFragment.kt")).first { it.isFile }.readText()
        catalogs[0].keys.filter { it.endsWith("_title") }.forEach { assertTrue(source.contains("R.string.$it")) }
        assertTrue(source.contains("currentHeader")) // Search preserves category association.
    }
    @Test fun localizedTopicsAndTechnicalAliasesRemainSearchable() {
        assertTrue(DocSearch.matches("  re2 ", "多行规则", "Go RE2 不支持前后查找"))
        assertTrue(DocSearch.matches("跨手机", "跨手机备份与 WebDAV"))
        assertTrue(DocSearch.matches("yacd", "Clash API", "YACD 9090"))
        assertTrue(DocSearch.matches("", "anything"))
        assertFalse(DocSearch.matches("ICMP", "HTTP handshake"))
    }
}
