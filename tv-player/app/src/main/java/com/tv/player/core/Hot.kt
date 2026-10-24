package com.tv.player.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hot charts. CMS sources do not track popularity (their hit counters are all 0), so the rankings come from
 * Douban's public chart endpoints; tapping a title then searches it across every source.
 */
object Hot {
    const val IMAGE_REFERER = "https://m.douban.com/"
    private const val FRESH_MS = 3 * 3600_000L
    private const val RECENT = "最近更新"
    private const val SHORT = "短剧"
    private val charts = listOf(
        "movie_real_time_hotest" to "热播电影",
        "tv_real_time_hotest" to "热播剧集",
        "show_hot" to "热播综艺",
        "tv_animation" to "热播动漫",
    )

    /** [rating] and [sub] (year) come from Douban; [remarks] (e.g. "更新至第06集") from a source. */
    class Item(val title: String, val pic: String, val rating: Double, val sub: String, val remarks: String = "")

    class Section(val title: String, val items: List<Item>)

    private val order = listOf("热播剧集", "热播电影", "热播综艺", "热播动漫", SHORT, RECENT)

    private fun List<Section>.ordered() = sortedBy { order.indexOf(it.title).let { i -> if (i < 0) order.size else i } }

    fun cached(): List<Section>? = if (Store.hotMode() == "cms") null else
        Store.hotCache()?.let { runCatching { parse(it.second) }.getOrNull() }?.takeIf { it.isNotEmpty() }?.ordered()

    /** Fresh cache is returned without touching the network; otherwise downloads. Null when nothing could be loaded. */
    suspend fun refresh(force: Boolean = false): List<Section>? {
        if (Store.hotMode() == "cms") return latest(force)
        // The short-drama and recent-updates tabs are fetched alongside the charts so a slow source cannot hold the charts back.
        val (charts, extra) = coroutineScope {
            val c = async { douban(force) }
            // Two calls per source; bounded as a whole so a few slow sources cannot keep the charts waiting.
            val s = async { withTimeoutOrNull(12_000) { runCatching { shortDramas(force) }.getOrNull() } }
            val r = async { runCatching { recent(force) }.getOrNull() }
            c.await() to listOfNotNull(s.await(), r.await())
        }
        if (charts == null) return latest(force)
        return (charts + extra).ordered()
    }

    private var shortAt = 0L
    private var shortCache: Section? = null
    /** Category id of "短剧" per source api; -1 when the source has no such category. */
    private val shortCategory = HashMap<String, Int>()

    /**
     * Short dramas (短剧) cannot be found by searching: nobody knows their titles. The sources carry them as a category
     * of their own, so this tab lists what each source put there most recently, interleaved and de-duplicated.
     */
    private suspend fun shortDramas(force: Boolean): Section? {
        shortCache?.let { if (!force && System.currentTimeMillis() - shortAt < 20 * 60_000L) return it }
        val sources = Store.sources().take(12)
        val groups = coroutineScope { sources.map { s -> async { runCatching { shortOf(s) }.getOrDefault(emptyList()) } }.awaitAll() }
        val mixed = ArrayList<Item>()
        for (i in 0 until (groups.maxOfOrNull { it.size } ?: 0)) groups.forEach { g -> g.getOrNull(i)?.let(mixed::add) }
        val items = mixed.distinctBy { Search.normalize(it.title) }.take(40)
        if (items.isEmpty()) return null
        return Section(SHORT, items).also { shortCache = it; shortAt = System.currentTimeMillis() }
    }

    private suspend fun shortOf(s: com.tv.player.data.VodSource): List<Item> {
        val sep = if ('?' in s.api) "&" else "?"
        val type = shortCategory[s.api] ?: run {
            val r = Http.get(s.api + sep + "ac=list", timeoutMs = 9_000)
            // A failed or odd answer is not remembered: the source may just be slow right now.
            val classes = (if (r.status == 200) runCatching { JSONObject(r.text).optJSONArray("class") }.getOrNull() else null) ?: return emptyList()
            var found = -1
            for (i in 0 until classes.length()) {
                val c = classes.optJSONObject(i) ?: continue
                val name = c.optString("type_name")
                // "擦边短剧" and the like are the adult corner of the category; the plain one comes first in every list.
                if (SHORT in name && !Cms.adult.containsMatchIn(name)) { found = c.optInt("type_id", -1); break }
            }
            shortCategory[s.api] = found
            found
        }
        if (type < 0) return emptyList()
        val r = Http.get(s.api + sep + "ac=videolist&t=$type&pg=1", timeoutMs = 9_000)
        if (r.status != 200) return emptyList()
        val list = JSONObject(r.text).optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val name = Cms.cleanTitle(o.optString("vod_name"))
            val pic = o.optString("vod_pic").trim()
            if (name.isEmpty() || pic.isEmpty() || Cms.adult.containsMatchIn(o.optString("type_name"))) return@mapNotNull null
            Item(name, pic, 0.0, "", o.optString("vod_remarks").trim())
        }
    }

    /** One mixed tab of what the sources updated most recently, series / movies / variety / anime interleaved. */
    private suspend fun recent(force: Boolean): Section? {
        // Short dramas have a tab of their own and would otherwise flood this one (the sources update them all day).
        val groups = latest(force)?.filter { it.title != "$RECENT · $SHORT" }?.map { it.items } ?: return null
        val mixed = ArrayList<Item>()
        for (i in 0 until (groups.maxOfOrNull { it.size } ?: 0)) groups.forEach { g -> g.getOrNull(i)?.let(mixed::add) }
        val items = mixed.distinctBy { Search.normalize(it.title) }.take(40)
        return if (items.isEmpty()) null else Section(RECENT, items)
    }

    private suspend fun douban(force: Boolean): List<Section>? {
        val cache = Store.hotCache()
        if (!force && cache != null && System.currentTimeMillis() - cache.first < FRESH_MS) {
            runCatching { parse(cache.second) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it.ordered() }
        }
        val sections = coroutineScope { charts.map { (id, title) -> async { fetchOne(id, title) } }.awaitAll() }.filterNotNull()
        if (sections.isEmpty()) return null
        Store.saveHot(serialize(sections))
        return sections.ordered()
    }

    // ---- fallback: the sources' own latest updates -----------------------------------------------------------------

    private var latestAt = 0L
    private var latestCache: List<Section>? = null
    private val adult get() = Cms.adult

    /**
     * For networks that cannot reach Douban. Not a popularity ranking, just what the sources updated most recently,
     * sorted into series / movies / variety / anime by their category names.
     */
    private suspend fun latest(force: Boolean): List<Section>? {
        latestCache?.let { if (!force && System.currentTimeMillis() - latestAt < 20 * 60_000L) return it }
        val sources = Store.sources().take(6)
        val rows = coroutineScope { sources.map { s -> async { runCatching { latestOf(s) }.getOrDefault(emptyList()) } }.awaitAll() }.flatten()
        val sections = listOf("剧集", "电影", "综艺", "动漫", SHORT).mapNotNull { kind ->
            val items = rows.filter { it.first == kind }.map { it.second }.distinctBy { Search.normalize(it.title) }.take(20)
            if (items.isEmpty()) null else Section("最近更新 · $kind", items)
        }
        if (sections.isEmpty()) return null
        latestCache = sections
        latestAt = System.currentTimeMillis()
        return sections
    }

    private suspend fun latestOf(s: com.tv.player.data.VodSource): List<Pair<String, Item>> {
        val url = s.api + (if ('?' in s.api) "&" else "?") + "ac=videolist&pg=1"
        val r = Http.get(url, timeoutMs = 9_000)
        if (r.status != 200) return emptyList()
        val list = JSONObject(r.text).optJSONArray("list") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val name = Cms.cleanTitle(o.optString("vod_name"))
            val type = o.optString("type_name")
            if (name.isEmpty() || adult.containsMatchIn(type)) return@mapNotNull null
            val kind = Cms.kindOf(type).ifEmpty { return@mapNotNull null }
            val pic = o.optString("vod_pic").trim()
            if (pic.isEmpty()) return@mapNotNull null // a poster row full of grey boxes is worse than a shorter row
            kind to Item(name, pic, 0.0, "", o.optString("vod_remarks").trim())
        }
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
                Item(name, pic, o.optJSONObject("rating")?.optDouble("value", 0.0) ?: 0.0, o.optString("card_subtitle").substringBefore(" / "))
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
