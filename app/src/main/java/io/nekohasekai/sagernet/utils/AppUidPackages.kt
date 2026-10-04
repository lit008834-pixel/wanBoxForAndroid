// @author 雾晚
package io.nekohasekai.sagernet.utils

/** Immutable owner index; full UID wins, app-ID fallback covers other Android users. @author 雾晚 */
object AppUidPackages {
    fun snapshot(entries: List<Pair<String, Int>>): Map<Int, Set<String>> =
        entries.groupBy({ it.second }, { it.first }).mapValues { it.value.toSortedSet().toSet() }

    fun names(index: Map<Int, Set<String>>, uid: Int): Set<String> {
        if (uid < 0) return emptySet()
        return index[uid] ?: index[uid % 100000].orEmpty()
    }
}
