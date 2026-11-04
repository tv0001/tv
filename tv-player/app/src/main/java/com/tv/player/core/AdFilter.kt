package com.tv.player.core

import org.json.JSONObject
import java.math.BigDecimal

/**
 * 按订阅里的 `rules` 剔除 m3u8 里的广告分片（规则写法和 TVBox / Box 一致）：
 * - `hosts`：播放列表所在主机，包含即算匹配，也可以写通配符（`vip.lz*`）或正则（`.*boku.*`）；
 * - `regex` 里带 `#EXT-X-DISCONTINUITY` / `#EXTINF` 的：正则匹配到的整段删掉；
 * - `regex` 里是数字的（如 `15.1666`）：两个 DISCONTINUITY 之间的一段，第一个分片时长或整段总时长以它开头就删掉，
 *   写成 `-8.16` 时比的是这一段最后一个分片。
 * 其余规则（嗅探、代理等）和广告无关，忽略。
 *
 * 只处理点播的列表（有 `#EXT-X-ENDLIST`）：直播列表会刷新，删了分片会打乱分片序号。
 * 删掉的超过一半时长、或者一个分片都不剩，说明规则不适合这个片子，原样返回。
 */
object AdFilter {
    class Rule(val name: String, private val hosts: List<Regex>, val patterns: List<Regex>, val durations: List<String>) {
        fun matches(host: String) = hosts.any { it.containsMatchIn(host) }
    }

    class Result(val text: String, val rule: String, val removed: Int)

    private const val DISCONTINUITY = "#EXT-X-DISCONTINUITY"
    private const val ENDLIST = "#EXT-X-ENDLIST"
    private val EXTINF = Regex("#EXTINF:([\\d.]+)")
    // 一段：从一个 DISCONTINUITY 到下一个（不算 #EXT-X-DISCONTINUITY-SEQUENCE）
    private val BLOCK = Regex("#EXT-X-DISCONTINUITY(?!-)[\\s\\S]*?(?=#EXT-X-DISCONTINUITY(?!-)|$)")

    @Volatile private var cached: Pair<String, List<Rule>>? = null

    /** 订阅配置里的去广告规则；配置不变时只解析一次。 */
    fun rules(config: String): List<Rule> {
        cached?.let { (text, rules) -> if (text === config || text == config) return rules }
        val rules = runCatching { parse(config) }.getOrDefault(emptyList())
        cached = config to rules
        return rules
    }

    fun parse(config: String): List<Rule> {
        val arr = JSONObject(config).optJSONArray("rules") ?: return emptyList()
        val out = ArrayList<Rule>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val hosts = o.optJSONArray("hosts")?.let { a -> (0 until a.length()).mapNotNull { hostPattern(a.optString(it)) } }.orEmpty()
            val regex = o.optJSONArray("regex")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            val patterns = regex.filter { it.contains(DISCONTINUITY) || it.contains("#EXTINF") }
                .mapNotNull { runCatching { Regex(it) }.getOrNull() }
            val durations = regex.filter { (it.removePrefix("-").toDoubleOrNull() ?: 0.0) != 0.0 }
            if (hosts.isEmpty() || patterns.isEmpty() && durations.isEmpty()) continue
            out += Rule(o.optString("name"), hosts, patterns, durations)
        }
        return out
    }

    /** `vip.lz*`、`*.ffzy*` 是通配符；带 `.*` 的按正则；其余按字面包含。 */
    private fun hostPattern(raw: String): Regex? {
        val h = raw.trim().lowercase()
        if (h.isEmpty()) return null
        return when {
            h.contains(".*") -> runCatching { Regex(h) }.getOrNull()
            h.contains('*') -> Regex(h.split('*').joinToString(".*") { Regex.escape(it) })
            else -> Regex(Regex.escape(h))
        }
    }

    /** 用 [host] 对应的规则清理一份 m3u8；没有规则、不是点播列表或什么都没删时返回 null。 */
    fun clean(text: String, host: String, rules: List<Rule>): Result? {
        if (!text.contains(ENDLIST) || !text.contains("#EXTINF")) return null
        val rule = rules.firstOrNull { it.matches(host.lowercase()) } ?: return null
        val src = text.replace("\r\n", "\n")
        var out = src
        for (p in rule.patterns) out = p.replace(out) { m -> if (m.value.contains(ENDLIST)) ENDLIST else "" }
        if (rule.durations.isNotEmpty()) out = byDuration(out, rule.durations)
        val before = EXTINF.findAll(src).toList()
        val after = EXTINF.findAll(out).toList()
        if (after.size == before.size) return null
        val total = before.sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
        val kept = after.sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
        if (after.isEmpty() || kept < total / 2) return null
        return Result(out, rule.name, before.size - after.size)
    }

    private fun byDuration(text: String, ads: List<String>): String = BLOCK.replace(text) { m ->
        val times = EXTINF.findAll(m.value).mapNotNull { it.groupValues[1].toBigDecimalOrNull() }.toList()
        if (times.isEmpty()) return@replace m.value
        val first = times.first().toString()
        val last = times.last().toString()
        val sum = times.fold(BigDecimal.ZERO, BigDecimal::add).toString()
        val ad = ads.any { a -> if (a.startsWith("-")) last.startsWith(a.substring(1)) else first.startsWith(a) || sum.startsWith(a) }
        when {
            !ad -> m.value
            m.value.contains(ENDLIST) -> "$ENDLIST\n"
            else -> ""
        }
    }
}
