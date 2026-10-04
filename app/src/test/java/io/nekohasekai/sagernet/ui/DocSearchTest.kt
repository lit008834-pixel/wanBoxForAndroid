// @author 雾晚
package io.nekohasekai.sagernet.ui

import org.junit.Assert.*
import org.junit.Test

/** @author 雾晚 */
class DocSearchTest {
    @Test fun localizedTopicsAndTechnicalAliasesRemainSearchable() {
        assertTrue(DocSearch.matches("  re2 ", "多行规则", "Go RE2 不支持前后查找"))
        assertTrue(DocSearch.matches("跨手机", "跨手机备份与 WebDAV"))
        assertTrue(DocSearch.matches("yacd", "Clash API", "YACD 9090"))
        assertTrue(DocSearch.matches("", "anything"))
        assertFalse(DocSearch.matches("ICMP", "HTTP handshake"))
    }
}
