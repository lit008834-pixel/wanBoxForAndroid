// @author 雾晚
package io.nekohasekai.sagernet.bg

/** Session-local observation. Unknown members never inherit a previous selection.
 * @author 雾晚
 */
class ActiveOutboundTracker(private val names: Map<String, String>) {
    fun resolve(tag: String): String? = names[tag]?.takeIf { it.isNotBlank() }
}

/** Equality includes preferences through the rendered content; commit only after publish.
 * Callers serialize access with the notification builder lock.
 * @author 雾晚
 */
class NotificationContentCache {
    data class Content(val title: String, val text: String, val expanded: String)
    private var published: Content? = null
    fun changed(content: Content) = published != content
    fun committed(content: Content) { published = content }
    fun clear() { published = null }
}
