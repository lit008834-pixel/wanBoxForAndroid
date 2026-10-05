// @author 雾晚
package io.nekohasekai.sagernet.ui

import kotlin.math.pow
import kotlin.math.roundToInt

/** Static theme-derived material with a contrast-safe opaque fallback. @author 雾晚 */
internal object GlassPalette {
    private const val BLACK = -0x1000000
    private const val WHITE = -0x1
    data class Surface(val top: Int, val bottom: Int, val edge: Int)

    fun create(surface: Int, primary: Int, onSurface: Int, reduceEffects: Boolean): Surface {
        val solid = surface or BLACK
        val edge = (onSurface and 0xFFFFFF) or (20 shl 24)
        if (reduceEffects || solid == BLACK || solid == WHITE) return Surface(solid, solid, edge)
        val bottom = blend(solid, primary or BLACK, 0.04)
        val translucent = Surface(withAlpha(solid, 245), withAlpha(bottom, 245), edge)
        // Mid-tone custom surfaces can straddle the black/white contrast boundary.
        return if (maxOf(contrast(BLACK, translucent), contrast(WHITE, translucent)) >= 4.5)
            translucent else Surface(solid, solid, edge)
    }

    fun text(candidate: Int, surface: Surface): Int {
        val opaque = candidate or BLACK
        if (contrast(opaque, surface) >= 5.0) return opaque
        return if (contrast(BLACK, surface) >= contrast(WHITE, surface)) BLACK else WHITE
    }

    /** Opaque text must contrast over either endpoint on any underlying RGB surface. @author 雾晚 */
    fun contrast(text: Int, surface: Surface): Double = listOf(surface.top, surface.bottom)
        .flatMap { color -> listOf(composite(color, BLACK), composite(color, WHITE)) }
        .minOf { background ->
            val a = luminance(text); val b = luminance(background)
            (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
        }

    fun composite(foreground: Int, background: Int): Int =
        blend(background or BLACK, foreground or BLACK, (foreground ushr 24) / 255.0)

    private fun withAlpha(color: Int, alpha: Int) = (color and 0xFFFFFF) or (alpha shl 24)
    private fun blend(a: Int, b: Int, fraction: Double): Int = BLACK or
        ((0..2).sumOf { channel ->
            val shift = channel * 8
            ((((a ushr shift) and 255) * (1.0 - fraction) +
                ((b ushr shift) and 255) * fraction).roundToInt()) shl shift
        })

    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((color ushr shift) and 255) / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return channel(16) * 0.2126 + channel(8) * 0.7152 + channel(0) * 0.0722
    }
}
