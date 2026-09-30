// @author 雾晚
package io.nekohasekai.sagernet.group

object SubscriptionCleanup {
    private val notice = Regex(
        "套餐到期|剩余流量|官网|防失联|通知|重置|过期时间|到期时间|流量剩余|订阅到期",
        RegexOption.IGNORE_CASE
    )
    private val decoration = Regex("[\\p{So}\\p{Cf}\\uFE0E\\uFE0F\\u20E3]+")
    private val whitespace = Regex("\\s+")

    fun isNotice(name: String): Boolean = notice.containsMatchIn(name)

    fun cleanName(name: String): String = whitespace.replace(
        decoration.replace(name, " "), " "
    ).trim().trim('·', '•', '|', ' ')
}
