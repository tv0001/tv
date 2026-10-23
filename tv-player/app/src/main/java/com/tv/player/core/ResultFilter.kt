package com.tv.player.core

import com.tv.player.data.VodGroup

/** Sorting and filtering of the merged search results by year, category, picture quality and source. */
class ResultFilter {
    var sort = 0 // index into SORTS
    var year = ""
    var kind = ""
    var quality = 0 // index into QUALITIES
    var source = ""

    companion object {
        val SORTS = listOf("综合", "年份最新", "源最多", "片名")
        val QUALITIES = listOf("全部", "高清以上", "排除抢先版")
        private val low = Regex("TC|TS|枪版|抢先|预告|HDTC|HDCAM|CAM", RegexOption.IGNORE_CASE)
        private val high = Regex("4K|2160|蓝光|BD|1080|超清|UHD|HD|720|高清|正片", RegexOption.IGNORE_CASE)
    }

    val active get() = sort != 0 || year.isNotEmpty() || kind.isNotEmpty() || quality != 0 || source.isNotEmpty()

    fun reset() {
        sort = 0; year = ""; kind = ""; quality = 0; source = ""
    }

    fun yearOf(g: VodGroup) = g.items.firstOrNull { it.year.isNotEmpty() }?.year.orEmpty()

    fun kindOf(g: VodGroup) = g.items.firstOrNull { it.kind.isNotEmpty() }?.kind.orEmpty()

    private fun remarksOf(g: VodGroup) = g.items.map { it.remarks }

    private fun isLow(g: VodGroup) = remarksOf(g).any { low.containsMatchIn(it) } && remarksOf(g).none { high.containsMatchIn(it) && !low.containsMatchIn(it) }

    private fun isHigh(g: VodGroup) = remarksOf(g).any { high.containsMatchIn(it) && !low.containsMatchIn(it) }

    fun years(all: List<VodGroup>) = all.map(::yearOf).filter { it.isNotEmpty() }.distinct().sortedDescending()

    fun kinds(all: List<VodGroup>) = all.map(::kindOf).filter { it.isNotEmpty() }.distinct()

    fun sources(all: List<VodGroup>) = all.flatMap { g -> g.items.map { it.sourceName } }.distinct().sorted()

    fun apply(all: List<VodGroup>): List<VodGroup> {
        val kept = all.filter { g ->
            (year.isEmpty() || yearOf(g) == year) &&
                (kind.isEmpty() || kindOf(g) == kind) &&
                (source.isEmpty() || g.items.any { it.sourceName == source }) &&
                when (quality) {
                    1 -> isHigh(g)
                    2 -> !isLow(g)
                    else -> true
                }
        }
        return when (sort) {
            1 -> kept.sortedByDescending { yearOf(it) }
            2 -> kept.sortedByDescending { it.items.size }
            3 -> kept.sortedBy { it.title }
            else -> kept
        }
    }
}
