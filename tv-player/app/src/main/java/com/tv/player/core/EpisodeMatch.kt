package com.tv.player.core

import com.tv.player.data.Episode
import java.text.Normalizer

/**
 * 换线路、换源时，在另一条线路里找「同一个视频」。以前直接用同一个序号，电视剧一般没事，综艺各源分集方式不一样
 * （有的拆上 / 下，有的多出加更版、纯享版，有的按日期命名），序号一错位就跳到了另一期。
 *
 * 按名字找：先比去掉写法差异后的名字（「第01集」=「第1集」=「01」，「第1期(上)」=「第1期上」），再比纯集数；
 * 找不到就返回 null，调用方宁可不用这条线路，也不放一个不同的视频。
 */
object EpisodeMatch {
    // 清晰度、字幕、完结这类标注不影响是哪一集
    private val noise = Regex("hd|高清|超清|标清|蓝光|\\d{3,4}p|4k|中字|无水印|大结局|完结|全剧终|end")
    // 合集「第1-2集」：数字之间留个记号，不然去掉标点后和「第12集」一样（日期「2026-11-08」有四位年份，不算）
    private val range = Regex("(?<![\\d-])(\\d{1,3})\\s*[-~～至到]\\s*(\\d{1,3})(?!\\d)")
    private val unitAfterNumber = Regex("(\\d+)[集话回]")
    private val punct = Regex("[\\s\\p{P}\\p{S}]")
    private val digits = Regex("\\d+")
    private val episodeNumber = Regex("^(?:第|ep|e)?(\\d+)([集话回期]?)$")

    // 同一个名字会被比很多次（每条线路的每一集）：记住算过的，长剧集换线时主线程不用反复跑正则
    private val keys = memo<String>()
    private val numbers = memo<Pair<Int, String>?>()

    /** 去掉写法差异后的名字：全角转半角、小写、去掉「第」和集 / 话 / 回、标点和空格、数字前面的 0。 */
    fun key(name: String): String = keys.getOrPut(name) {
        var s = range.replace(clean(name), "$1to$2")
        s = s.replace("第", "")
        s = unitAfterNumber.replace(s, "$1")
        s = punct.replace(s, "")
        digits.replace(s) { it.value.trimStart('0').ifEmpty { "0" } }
    }

    /** 只写了集数的名字（「第3集」「03」「EP3」「3话」→ 3 集，「第3期」→ 3 期）；还带别的字的（「第3期上」）是 null。 */
    fun number(name: String): Pair<Int, String>? = numbers.getOrPut(name) {
        val m = episodeNumber.find(punct.replace(range.replace(clean(name), "$1to$2"), ""))
        val n = m?.groupValues?.get(1)?.toIntOrNull()
        if (m == null || n == null) null else n to (if (m.groupValues[2] == "期") "期" else "集")
    }

    private fun clean(name: String) = noise.replace(Normalizer.normalize(name, Normalizer.Form.NFKC).lowercase(), "")

    private class Memo<V> {
        private val map = object : LinkedHashMap<String, Any?>(256, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any?>) = size > 4096
        }

        @Suppress("UNCHECKED_CAST")
        @Synchronized
        fun getOrPut(k: String, make: () -> V): V {
            if (map.containsKey(k)) return map[k] as V
            return make().also { map[k] = it }
        }
    }

    private fun <V> memo() = Memo<V>()

    /** [current] 里第 [ep] 个视频在 [other] 里的位置；对不上是 null。 */
    fun match(current: List<Episode>, ep: Int, other: List<Episode>): Int? {
        if (other.isEmpty()) return null
        val name = current.getOrNull(ep)?.name ?: return null
        // 电影：两边都只有一个视频，名字（「正片」「HD国语」）对不上也是同一部
        if (current.size == 1 && other.size == 1) return 0
        val k = key(name)
        if (k.isNotEmpty()) nearest(other.indices.filter { key(other[it].name) == k }, ep)?.let { return it }
        val n = number(name)
        if (n != null) {
            nearest(other.indices.filter { number(other[it].name) == n }, ep)?.let { return it }
            // 那边的名字里根本没有集数（比如都叫「高清」），两边一样多集时只能按位置
            if (n.second == "集" && other.size == current.size && other.none { number(it.name) != null }) return ep
        }
        // 这边没有名字：两边一样多集时按位置（和以前一样）
        if (k.isEmpty() && other.size == current.size) return ep
        return null
    }

    /** 同名的有几个时（比如几个都叫「正片」），取位置最接近的。 */
    private fun nearest(hits: List<Int>, ep: Int): Int? = hits.minByOrNull { kotlin.math.abs(it - ep) }
}
