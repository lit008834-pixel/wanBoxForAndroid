// @author 雾晚
package moe.matsuri.nb4a.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeColorInputTest {
    @Test
    fun acceptsOpaqueRgbHex() {
        assertEquals(0xFF0052D9.toInt(), ThemeColorInput.parse("#0052D9"))
        assertEquals(0xFF00E676.toInt(), ThemeColorInput.parse(" #00e676 "))
        assertEquals("#00E676", ThemeColorInput.format(0xFF00E676.toInt()))
    }

    @Test
    fun rejectsIncompleteOrAlphaHex() {
        assertNull(ThemeColorInput.parse("#12345"))
        assertNull(ThemeColorInput.parse("#FF0052D9"))
        assertNull(ThemeColorInput.parse("0052D9"))
        assertNull(ThemeColorInput.parse("#GGGGGG"))
    }
}
