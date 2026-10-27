package com.tv.player.core

import com.tv.player.data.VodGroup

/** Sorting and filtering of the merged search results by year, category, picture quality and source. */
class ResultFilter {
    var sort = 0 // index into SORTS
    var year = ""
    var kind = ""
    var quality = 0 // index into QUALITIES
    var source = ""
    var status = 0 // index into STATUSES: finished or still being updated, from the sources' remarks

    companion object {
        val STATUSES = listOf("全部", "已完结", "连载中")
        // "未完结" and "即将完结" contain "完结" and are not finished; "更新完毕", "第80集(完)", "80集全" are.
        private val unfinished = Regex("未完|即将完结|尚未")
        private val finished = Regex("完结|全集|全\\d+集|\\d+集全|\\d+集完|[(（]完[)）]|更新完|正片")
        private val running = Regex("更新|连载|第\\d+集")
        val SORTS = listOf("综合", "年份最新", "源最多", "片名")
        val QUALITIES = listOf("全部", "高清以上", "排除抢先版")
        private val low = Regex("TC|TS|枪版|抢先|预告|HDTC|HDCAM|CAM", RegexOption.IGNORE_CASE)
        private val high = Regex("4K|2160|蓝光|BD|1080|超清|UHD|HD|720|高清|正片", RegexOption.IGNORE_CASE)
    }

    val active get() = sort != 0 || year.isNotEmpty() || kind.isNotEmpty() || quality != 0 || source.isNotEmpty() || status != 0

    fun reset() {
        sort = 0; year = ""; kind = ""; quality = 0; source = ""; status = 0
    }

    fun yearOf(g: VodGroup) = g.items.firstOrNull { it.year.isNotEmpty() }?.year.orEmpty()

    fun kindOf(g: VodGroup) = g.items.firstOrNull { it.kind.isNotEmpty() }?.kind.orEmpty()

    private fun remarksOf(g: VodGroup) = g.items.map { it.remarks }

    private fun isLow(g: VodGroup) = remarksOf(g).any { low.containsMatchIn(it) } && remarksOf(g).none { high.containsMatchIn(it) && !low.containsMatchIn(it) }

    /** 1 finished, 2 still running, 0 cannot be told ("更新至第08集" runs, "全80集" / "已完结" / "正片" is done; a source that says it is done wins). */
    fun statusOf(g: VodGroup): Int {
        val notes = remarksOf(g)
        return when {
            notes.any { finished.containsMatchIn(it) && !unfinished.containsMatchIn(it) } -> 1
            notes.any { unfinished.containsMatchIn(it) || running.containsMatchIn(it) } -> 2
            else -> 0
        }
    }

    private fun isHigh(g: VodGroup) = remarksOf(g).any { high.containsMatchIn(it) && !low.containsMatchIn(it) }

    fun years(all: List<VodGroup>) = all.map(::yearOf).filter { it.isNotEmpty() }.distinct().sortedDescending()

    fun kinds(all: List<VodGroup>) = all.map(::kindOf).filter { it.isNotEmpty() }.distinct()

    fun sources(all: List<VodGroup>) = all.flatMap { g -> g.items.map { it.sourceName } }.distinct().sorted()

    fun apply(all: List<VodGroup>): List<VodGroup> {
        val kept = all.filter { g ->
            (year.isEmpty() || yearOf(g) == year) &&
                (kind.isEmpty() || kindOf(g) == kind) &&
                (source.isEmpty() || g.items.any { it.sourceName == source }) &&
                (status == 0 || statusOf(g) == status) &&
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
