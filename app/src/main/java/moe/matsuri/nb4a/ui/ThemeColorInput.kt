// @author 雾晚
package moe.matsuri.nb4a.ui

object ThemeColorInput {
    private val hexColor = Regex("^#[0-9a-fA-F]{6}$")

    fun parse(text: String): Int? {
        val value = text.trim()
        if (!hexColor.matches(value)) return null
        return 0xFF000000.toInt() or value.substring(1).toInt(16)
    }

    fun format(color: Int): String = "#%06X".format(color and 0xFFFFFF)
}
