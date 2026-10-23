package com.tv.player.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hot charts. CMS sources do not track popularity (their hit counters are all 0), so the rankings come from
 * Douban's public chart endpoints; tapping a title then searches it across every source.
 */
object Hot {
    const val IMAGE_REFERER = "https://m.douban.com/"
    private const val FRESH_MS = 3 * 3600_000L
    private val charts = listOf(
        "movie_real_time_hotest" to "热播电影",
        "tv_real_time_hotest" to "热播剧集",
        "show_hot" to "热播综艺",
        "tv_animation" to "热播动漫",
    )

    class Item(val title: String, val pic: String, val rating: Double, val sub: String)

    class Section(val title: String, val items: List<Item>)

    private val order = listOf("热播剧集", "热播电影", "热播综艺", "热播动漫")

    private fun List<Section>.ordered() = sortedBy { order.indexOf(it.title).let { i -> if (i < 0) order.size else i } }

    fun cached(): List<Section>? =
        Store.hotCache()?.let { runCatching { parse(it.second) }.getOrNull() }?.takeIf { it.isNotEmpty() }?.ordered()

    /** Fresh cache is returned without touching the network; otherwise downloads. Null when nothing could be loaded. */
    suspend fun refresh(force: Boolean = false): List<Section>? {
        val cache = Store.hotCache()
        if (!force && cache != null && System.currentTimeMillis() - cache.first < FRESH_MS) {
            runCatching { parse(cache.second) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it.ordered() }
        }
        val sections = coroutineScope { charts.map { (id, title) -> async { fetchOne(id, title) } }.awaitAll() }.filterNotNull()
        if (sections.isEmpty()) return null
        Store.saveHot(serialize(sections))
        return sections.ordered()
    }

    private suspend fun fetchOne(id: String, title: String): Section? {
        val r = Http.get(
            "https://m.douban.com/rexxar/api/v2/subject_collection/$id/items?start=0&count=20",
            timeoutMs = 12_000,
            headers = mapOf("Referer" to IMAGE_REFERER),
        )
        if (r.status != 200) return null
        return try {
            val arr = JSONObject(r.text).optJSONArray("subject_collection_items") ?: return null
            val items = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("title").trim()
                if (name.isEmpty()) return@mapNotNull null
                val pic = o.optJSONObject("pic")?.optString("large")?.ifEmpty { null } ?: o.optString("cover_url")
                Item(name, pic, o.optJSONObject("rating")?.optDouble("value", 0.0) ?: 0.0, o.optString("card_subtitle"))
            }
            if (items.isEmpty()) null else Section(title, items)
        } catch (e: Exception) {
            null
        }
    }

    private fun serialize(sections: List<Section>): String = JSONArray().also { all ->
        for (s in sections) all.put(
            JSONObject().put("t", s.title).put(
                "i",
                JSONArray().also { a ->
                    s.items.forEach { a.put(JSONObject().put("t", it.title).put("p", it.pic).put("r", it.rating).put("s", it.sub)) }
                },
            ),
        )
    }.toString()

    private fun parse(text: String): List<Section> {
        val all = JSONArray(text)
        return (0 until all.length()).map { i ->
            val s = all.getJSONObject(i)
            val a = s.getJSONArray("i")
            Section(s.getString("t"), (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Item(o.getString("t"), o.optString("p"), o.optDouble("r"), o.optString("s"))
            })
        }
    }
}
