package com.tv.player.core

/**
 * Searching with a sentence ("想看一部女主重生的短剧", "2023年的悬疑电影") without any model: the filler is dropped, the
 * category and year are noted so those titles come first, and what is left is searched like any keyword. Plain titles
 * are left exactly as they are: only a sentence that carries a marker is taken apart ("短剧之王" is a title, not a
 * category plus "之王").
 */
object Smart {
    /** [query] is what to search; [kind] ("短剧", "电影", …) and [year] only decide the order, they never filter. */
    class Parsed(val query: String, val kind: String, val year: String, val extras: List<String> = emptyList())

    private val prefix = Regex("^(?:我想看看?|我要看|我想找|想看看?|要看|想找|帮我找|帮我搜|给我找|给我来|找一部|找个|来一部|来个|搜一下|搜索|推荐一部|推荐几部|推荐个|有没有)")

    private val kinds = listOf(
        Regex("短剧") to "短剧",
        Regex("电视剧|连续剧|剧集") to "剧集",
        Regex("电影|影片") to "电影",
        Regex("综艺") to "综艺",
        Regex("动漫|动画片?|番剧") to "动漫",
    )

    private val year = Regex("(?<!\\d)((?:19|20)\\d{2})\\s*年(?!代)(?:的|版)?|(?:^|\\s)((?:19|20)\\d{2})(?:\\s|$)")

    /** Words that describe what is wanted rather than name it; they follow-up with an optional "的". */
    private val descriptors = Regex("(?:女主角|男主角|女主|男主|主角|剧情|故事|讲述|关于|类似|好看|最新|热门|推荐|高分|经典|那种|比较|一部|一个|几部|这部|那部)+的?")

    fun parse(input: String): Parsed {
        var s = Search.clean(input)
        // A bare year is a title ("2012", "1917"), not a sentence about a year.
        if (Regex("^(?:19|20)\\d{2}$").matches(s)) return Parsed(s, "", "")
        var marked = false

        prefix.find(s)?.let { s = s.substring(it.range.last + 1).trim(); marked = true }

        var yearFound = ""
        year.find(s)?.let { m ->
            yearFound = m.groupValues[1].ifEmpty { m.groupValues[2] }
            s = s.removeRange(m.range).trim()
            marked = true
        }

        var kind = ""
        var kindText = ""
        for ((re, name) in kinds) {
            // A category word counts at the end of a sentence, after "的" (when something longer than one character comes
            // before it: "我的电影" is a title) or after a space, or at the start before a space ("短剧 重生"). Inside a bare
            // title it does not.
            val end = Regex("(的|\\s)?(?:$re)$").find(s)
            val start = Regex("^(?:$re)\\s+").find(s)
            val hit = when {
                end != null && (end.range.first > 0 || marked) && when (end.groupValues[1]) {
                    "" -> marked
                    "的" -> marked || end.range.first >= 2
                    else -> true
                } -> end
                start != null -> start
                else -> null
            } ?: continue
            kind = name
            kindText = hit.value.trim().removePrefix("的").trim()
            s = s.removeRange(hit.range).trim()
            marked = true
            break
        }

        val cleaned = Search.clean(input)
        if (!marked) {
            // Not a sentence: search it as typed, and keep the version without descriptions as one more thing to try.
            val bare = stripDescriptors(s)
            return Parsed(s, "", "", if (bare.isNotEmpty() && bare != s) listOf(bare) else emptyList())
        }
        var rest = stripDescriptors(s).trim('的').trim()
        // Nothing left but a year, a category word or bare descriptions ("好看的电影"): search the year, or the category
        // word itself, rather than "好看".
        if (rest.isEmpty()) rest = yearFound.ifEmpty { kindText.ifEmpty { s.ifEmpty { cleaned } } }
        // The sentence as typed stays as a last resort, in case taking it apart cut into a title.
        return Parsed(rest, kind, yearFound, if (rest != cleaned) listOf(cleaned) else emptyList())
    }

    fun stripDescriptors(s: String): String = descriptors.replace(s, " ").replace(Regex("\\s+"), " ").trim().trim('的').trim()

    /**
     * The closest of [candidates] (titles the user has searched, watched or seen on the charts) to a [query] that found
     * nothing: one wrong, missing or extra character in a short title, two in a longer one. Null when there is none.
     */
    fun nearest(query: String, candidates: Collection<String>): String? {
        val q = Search.canon(query)
        if (q.length < 3) return null
        val limit = if (q.length <= 4) 1 else 2
        var best: String? = null
        var bestDistance = limit + 1
        for (c in candidates) {
            val n = Search.canon(c)
            if (n == q || kotlin.math.abs(n.length - q.length) > limit) continue
            val d = distance(q, n, bestDistance - 1)
            if (d < bestDistance) {
                bestDistance = d
                best = c
            }
        }
        return best
    }

    /** Edit distance, giving up (returning max + 1) once it is certain to exceed [max]. */
    private fun distance(a: String, b: String, max: Int): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            var low = cur[0]
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                low = minOf(low, cur[j])
            }
            if (low > max) return max + 1
            prev = cur
        }
        return prev[b.length]
    }
}
