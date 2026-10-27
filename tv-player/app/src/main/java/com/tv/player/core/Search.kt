package com.tv.player.core

import com.tv.player.data.VodGroup
import com.tv.player.data.VodItem
import com.tv.player.data.VodSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.text.Normalizer

class Hit(val source: VodSource, val items: List<VodItem>, val error: String?, val ms: Long)

/** After 3 straight failures a source is skipped for 30 minutes, so one dead site cannot slow every search. */
object SourceHealth {
    private const val COOLDOWN_MS = 30 * 60_000L
    private val fails = HashMap<String, Int>()
    private val until = HashMap<String, Long>()

    @Synchronized fun available(key: String) = (until[key] ?: 0L) <= System.currentTimeMillis()

    @Synchronized fun ok(key: String) {
        fails.remove(key)
        until.remove(key)
    }

    @Synchronized fun fail(key: String) {
        val n = (fails[key] ?: 0) + 1
        fails[key] = n
        if (n >= 3) until[key] = System.currentTimeMillis() + COOLDOWN_MS
    }
}

object Search {
    /**
     * Queries every source in parallel, each with its own deadline; emits one [Hit] per source as it finishes. One user
     * search counts once towards a source's cool-down: the shorter retry rounds pass [trackHealth] = false.
     */
    fun all(sources: List<VodSource>, keyword: String, perSourceMs: Long = 10_000, trackHealth: Boolean = true): Flow<Hit> = channelFlow {
        val succeeded = AtomicInteger()
        val failed = ConcurrentLinkedQueue<String>()
        coroutineScope {
            for (s in sources) launch(Dispatchers.IO) {
                if (!SourceHealth.available(s.key)) {
                    send(Hit(s, emptyList(), "冷却中", 0))
                    return@launch
                }
                val t0 = System.currentTimeMillis()
                val hit = try {
                    val items = withTimeout(perSourceMs) { Cms.search(s, keyword) }.filter { matches(keyword, it.title) }
                    SourceHealth.ok(s.key)
                    succeeded.incrementAndGet()
                    Hit(s, items, null, System.currentTimeMillis() - t0)
                } catch (e: TimeoutCancellationException) {
                    failed += s.key
                    Hit(s, emptyList(), "超时", System.currentTimeMillis() - t0)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed += s.key
                    Hit(s, emptyList(), e.message ?: e.javaClass.simpleName, System.currentTimeMillis() - t0)
                }
                send(hit)
            }
        }
        // If nothing answered, the network is probably down: do not put every source in cool-down for it.
        if (trackHealth && succeeded.get() > 0) failed.forEach { SourceHealth.fail(it) }
    }

    private val notLetterOrDigit = Regex("[^\\p{L}\\p{N}]")
    private val quotes = Regex("[《》〈〉「」『』“”\"]")
    private val spaces = Regex("\\s+")

    fun normalize(title: String): String =
        Normalizer.normalize(title, Normalizer.Form.NFKC).lowercase().replace(notLetterOrDigit, "")

    private val seasonMark = Regex("第([一二两三四五六七八九十]{1,3})([季部集期])")

    /** Reads 一..九十九; null for anything else (百, 千, a digit string like 一二). */
    private fun chineseNumber(s: String): Int? {
        fun digit(c: Char) = if (c == '两') 2 else "一二三四五六七八九".indexOf(c).takeIf { it >= 0 }?.plus(1)
        val ten = s.indexOf('十')
        return when {
            ten < 0 -> if (s.length == 1) digit(s[0]) else null
            ten > 1 || s.length > ten + 2 -> null
            else -> {
                val tens = if (ten == 0) 1 else digit(s[0]) ?: return null
                val ones = if (s.length == ten + 1) 0 else digit(s[ten + 1]) ?: return null
                tens * 10 + ones
            }
        }
    }

    /** [normalize] with "第二季" read as "第2季", so sources that spell a season differently still match each other. */
    fun canon(title: String): String =
        seasonMark.replace(normalize(title)) { m -> chineseNumber(m.groupValues[1])?.let { "第$it${m.groupValues[2]}" } ?: m.value }

    /** What is sent to the sources: 《》 and double quotes dropped, full-width characters made plain, white space collapsed. */
    fun clean(keyword: String): String {
        // Full-width letters and digits become plain ones, as do ideographic spaces; punctuation stays as typed, since
        // a source compares the keyword with its titles letter by letter ("：" is not ":").
        val plain = StringBuilder(keyword.length)
        for (c in keyword) plain.append(if (c in '！'..'～' && c.isLetterOrDigit()) (c.code - 0xFEE0).toChar() else if (c == '\u3000') ' ' else c)
        return plain.toString().replace(quotes, " ").replace(spaces, " ").trim()
    }

    private val seasonTail = Regex("(?:\\s*第[一二两三四五六七八九十0-9]+[季部集期]|\\s+S\\d{1,2}\\b).*$", RegexOption.IGNORE_CASE)
    private val fillerTail = Regex("\\s*(?:全集|完整版|高清|超清|蓝光|在线观看|免费观看|免费|国语版?|粤语版?|电视剧|电影|最新)$")

    /**
     * Shorter queries to fall back on when the whole keyword finds nothing, most specific first (at most three): without
     * the season and without words like "全集", then the first word, then the first four characters. This is what the
     * "try fewer words" advice asks the user to do by hand.
     */
    fun relaxed(keyword: String, withPrefix: Boolean = true): List<String> {
        val k = clean(keyword)
        val out = LinkedHashSet<String>()
        var base = seasonTail.replace(k, "").trim()
        while (true) {
            val next = fillerTail.replace(base, "").trim()
            if (next == base) break
            base = next
        }
        if (base.isNotEmpty()) out += base
        // Cutting a Latin title short ("Spider", "The") only finds unrelated ones; this is for Chinese titles.
        if (base.any { it in '\u4e00'..'\u9fff' }) {
            base.substringBefore(' ').takeIf { it.length >= 2 }?.let { out += it }
            val compact = base.replace(" ", "")
            if (withPrefix && compact.length >= 6) out += compact.take(4)
        }
        out.remove(k)
        return out.take(3)
    }

    /** Some sources ignore the keyword and return their latest items, so keep only titles that really match. */
    fun matches(keyword: String, title: String): Boolean {
        val k = canon(keyword)
        val t = canon(title)
        if (k.isEmpty()) return true
        return t.contains(k) || (t.length >= 2 && k.contains(t))
    }

    private val derivative = Regex("解说|花絮|预告|混剪|盘点|解读")

    /** 0 exact title, 1 begins with the keyword, 2 contains it, 3 neither; both sides already through [canon]. */
    internal fun tierOf(k: String, t: String): Int = when {
        t == k -> 0
        t.startsWith(k) -> 1
        t.contains(k) -> 2
        else -> 3
    }

    /**
     * Smaller is better: exact title, then titles that begin with the keyword, then ones that contain it, then the rest.
     * With several keywords (what was typed, and the shorter one a retry fell back to) the best of them counts.
     */
    internal fun rank(keywords: List<String>, title: String, canonKeywords: List<String> = keywords.map(::canon), canonTitle: String = canon(title)): Int {
        val tier = canonKeywords.minOf { tierOf(it, canonTitle) }
        // A recap or trailer of the title is rarely what is wanted, unless a keyword asks for one.
        return if (derivative.containsMatchIn(title) && keywords.none { derivative.containsMatchIn(it) }) tier + 4 else tier
    }
}

/**
 * Merges hits from different sources that share a normalized title. With [dedupe] an entry that is added again (same
 * source, same id) is kept once; the search page leaves it off and merges exactly as it always did.
 */
class Grouper(private val dedupe: Boolean = false) {
    private class Acc(val title: String) {
        val items = ArrayList<VodItem>()
    }

    private val groups = LinkedHashMap<String, Acc>()

    fun add(items: List<VodItem>) {
        for (i in items) {
            val key = Search.normalize(i.title).ifEmpty { i.title }
            // With dedupe: the same entry can arrive twice (two keywords of one theme both match its title).
            val acc = groups.getOrPut(key) { Acc(i.title) }
            if (!dedupe || acc.items.none { it.sourceKey == i.sourceKey && it.id == i.id }) acc.items += i
        }
    }

    val size get() = groups.size

    /** Exact title first, then titles starting with the keyword, then ones containing it; within a tier, found in more sources first, then the closer length. */
    fun snapshot(keyword: String, alsoLike: List<String> = emptyList(), kind: String = "", year: String = ""): List<VodGroup> {
        val keywords = listOf(keyword) + alsoLike
        val canonKeywords = keywords.map(Search::canon)
        // Each group's rank is worked out once, not on every comparison of the sort. A category that was asked for
        // ("短剧") puts the titles that have it first; a year only breaks ties among equally good titles. Both are an
        // order, not a filter, so nothing disappears.
        class Entry(val group: VodGroup, val rank: Int, val best: Int, val gap: Int, val kindMiss: Int, val yearMiss: Int)
        return groups.entries
            .map { (key, acc) ->
                val withPic = acc.items.firstOrNull { it.pic.isNotEmpty() }
                val remarks = acc.items.firstOrNull { it.remarks.isNotEmpty() }?.remarks ?: ""
                val group = VodGroup(key, acc.title, withPic?.pic ?: "", remarks, acc.items.toList())
                val canonTitle = Search.canon(acc.title)
                // The keyword the title matches best (the one typed, before the ones a retry fell back to, when equal).
                val best = canonKeywords.indices.minByOrNull { Search.tierOf(canonKeywords[it], canonTitle) }!!
                Entry(
                    group,
                    Search.rank(keywords, acc.title, canonKeywords, canonTitle),
                    best,
                    kotlin.math.abs(canonTitle.length - canonKeywords[best].length),
                    if (kind.isNotEmpty() && acc.items.none { it.kind == kind }) 1 else 0,
                    if (year.isNotEmpty() && acc.items.none { it.year == year }) 1 else 0,
                )
            }
            .sortedWith(compareBy<Entry> { it.kindMiss }.thenBy { it.rank }.thenBy { it.best }.thenBy { it.yearMiss }.thenByDescending { it.group.items.size }.thenBy { it.gap })
            .map { it.group }
    }
}
