// @author 雾晚
package io.nekohasekai.sagernet.ui

import org.junit.Assert.*
import org.junit.Test

/** Tests material contrast over worst-case substrates without Android stubs. @author 雾晚 */
class GlassPaletteTest {
    @Test fun themedTextRemainsReadableAcrossSurfaceAndCustomPrimaryColors() {
        val primaries = listOf(0xFF0052D9.toInt(), 0xFFFFEB3B.toInt(), 0xFFF44336.toInt(),
            0xFF00BFA5.toInt(), 0xFF6200EE.toInt(), 0, -1)
        for (gray in 0..255) for (primary in primaries) {
            val surface = 0xFF000000.toInt() or (gray * 0x010101)
            val palette = GlassPalette.create(surface, primary, -1, false)
            for (candidate in listOf(-1, 0xFF000000.toInt(), 0xFF777777.toInt(), primary)) {
                val text = GlassPalette.text(candidate, palette)
                assertTrue("Contrast failure at gray=$gray", GlassPalette.contrast(text, palette) >= 4.5)
            }
        }
    }

    @Test fun reducedEffectsAndPureSurfacesAreOpaqueAndDoNotInheritAccentTints() {
        for (surface in listOf(-1, 0xFF000000.toInt(), 0xFFF4F4F7.toInt(), 0xFF1E1E1E.toInt())) {
            val reduced = GlassPalette.create(surface, 0xFFFF0000.toInt(), -1, true)
            assertEquals(surface, reduced.top); assertEquals(surface, reduced.bottom)
            if (surface == -1 || surface == 0xFF000000.toInt()) {
                assertEquals(reduced, GlassPalette.create(surface, 0xFFFF0000.toInt(), -1, false))
            }
        }
    }

    @Test fun saturatedUnderlyingContentDoesNotBreakKeyTextContrast() {
        for (surface in listOf(0xFFF4F4F7.toInt(), 0xFF1E1E1E.toInt(), 0xFF817374.toInt())) {
            val palette = GlassPalette.create(surface, 0xFF00FFFF.toInt(), -1, false)
            val text = GlassPalette.text(0xFF777777.toInt(), palette)
            for (underlay in listOf(-1, 0xFF000000.toInt(), 0xFFFF0000.toInt(),
                0xFF00FF00.toInt(), 0xFF0000FF.toInt())) {
                for (endpoint in listOf(palette.top, palette.bottom)) {
                    val opaque = GlassPalette.composite(endpoint, underlay)
                    assertTrue(GlassPalette.contrast(text, GlassPalette.Surface(opaque, opaque, 0)) >= 4.5)
                }
            }
        }
    }

    @Test fun recreatedThemeHasFreshColorsAndIndependentSurfaces() {
        val light = GlassPalette.create(0xFFF4F4F7.toInt(), 0xFF0052D9.toInt(), 0xFF202020.toInt(), false)
        val dark = GlassPalette.create(0xFF1E1E1E.toInt(), 0xFF00BFA5.toInt(), -1, false)
        assertNotEquals(light, dark)
        assertEquals(light, GlassPalette.create(0xFFF4F4F7.toInt(), 0xFF0052D9.toInt(), 0xFF202020.toInt(), false))
        assertEquals(245, light.top ushr 24)
        assertEquals(20, dark.edge ushr 24)
    }
}
