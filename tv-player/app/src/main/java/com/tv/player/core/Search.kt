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
    /** Queries every source in parallel, each with its own deadline; emits one [Hit] per source as it finishes. */
    fun all(sources: List<VodSource>, keyword: String, perSourceMs: Long = 10_000): Flow<Hit> = channelFlow {
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
        if (succeeded.get() > 0) failed.forEach { SourceHealth.fail(it) }
    }

    fun normalize(title: String): String =
        Normalizer.normalize(title, Normalizer.Form.NFKC).lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    /** Some sources ignore the keyword and return their latest items, so keep only titles that really match. */
    fun matches(keyword: String, title: String): Boolean {
        val k = normalize(keyword)
        val t = normalize(title)
        if (k.isEmpty()) return true
        return t.contains(k) || (t.length >= 2 && k.contains(t))
    }
}

/** Merges hits from different sources that share a normalized title. */
class Grouper {
    private class Acc(val title: String) {
        val items = ArrayList<VodItem>()
    }

    private val groups = LinkedHashMap<String, Acc>()

    fun add(items: List<VodItem>) {
        for (i in items) {
            val key = Search.normalize(i.title).ifEmpty { i.title }
            groups.getOrPut(key) { Acc(i.title) }.items += i
        }
    }

    val size get() = groups.size

    /** Exact title match first, then titles starting with the keyword, then titles found in more sources. */
    fun snapshot(keyword: String): List<VodGroup> {
        val k = Search.normalize(keyword)
        return groups.entries
            .map { (key, acc) ->
                val withPic = acc.items.firstOrNull { it.pic.isNotEmpty() }
                val remarks = acc.items.firstOrNull { it.remarks.isNotEmpty() }?.remarks ?: ""
                VodGroup(key, acc.title, withPic?.pic ?: "", remarks, acc.items.toList())
            }
            .sortedWith(
                compareByDescending<VodGroup> { Search.normalize(it.title) == k }
                    .thenByDescending { Search.normalize(it.title).startsWith(k) }
                    .thenByDescending { it.items.size },
            )
    }
}
