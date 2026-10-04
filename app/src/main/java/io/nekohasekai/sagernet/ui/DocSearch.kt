// @author 雾晚
package io.nekohasekai.sagernet.ui

/** Locale-independent matching of visible text and technical aliases.
 * @author 雾晚
 */
object DocSearch {
    fun matches(query: String, vararg fields: String): Boolean =
        query.isBlank() || fields.any { it.contains(query.trim(), ignoreCase = true) }
}
