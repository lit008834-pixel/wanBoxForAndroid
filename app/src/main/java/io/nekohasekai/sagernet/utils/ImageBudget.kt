// @author 雾晚
package io.nekohasekai.sagernet.utils

object ImageBudget {
    const val MAX_EDGE = 2048
    fun target(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0) { "无效图片尺寸" }
        val scale = minOf(1.0, MAX_EDGE.toDouble() / maxOf(width, height))
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }

    fun sample(width: Int, height: Int): Int {
        require(width > 0 && height > 0) { "无效图片尺寸" }
        var sample = 1
        while ((width.toLong() + sample - 1) / sample > MAX_EDGE ||
            (height.toLong() + sample - 1) / sample > MAX_EDGE) sample *= 2
        return sample
    }
}
