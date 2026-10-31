package com.tv.player.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 分类浏览：豆瓣「选影视」的片单（m.douban.com 的 recommend 接口），按类型、地区、年代等筛选，按热度 / 评分 / 时间排序。
 * 能选哪些筛选项由接口自己给（recommend_categories），所以豆瓣改了分类这里也跟着变。
 */
object Browse {
    /** 一组筛选项，如「类型」：全部、喜剧、爱情…… */
    class Category(val name: String, val options: List<String>)

    /** [raw] 是接口这一页给了几条（含跳过的片单、广告），下一页从 start + raw 开始。 */
    class Page(val items: List<Hot.Item>, val total: Int, val categories: List<Category>, val sorts: List<Pair<String, String>>, val raw: Int)

    const val PAGE = 30
    const val FORM = "形式"

    /** [kind] 是 movie（电影）或 tv（剧集、综艺、动画都在这里）；[selected] 里值为「全部」的不算。 */
    fun url(kind: String, selected: Map<String, String>, sort: String, start: Int): String {
        val picked = selected.filterValues { it.isNotEmpty() && it != "全部" }
        val categories = JSONObject().also { o -> picked.forEach { (k, v) -> o.put(k, v) } }.toString()
        return "https://m.douban.com/rexxar/api/v2/$kind/recommend?refresh=0&start=$start&count=$PAGE" +
            "&selected_categories=" + URLEncoder.encode(categories, "UTF-8") +
            "&uncollect=false&sort=" + URLEncoder.encode(sort, "UTF-8") +
            "&tags=" + URLEncoder.encode(picked.values.joinToString(","), "UTF-8")
    }

    suspend fun page(kind: String, selected: Map<String, String>, sort: String, start: Int): Page? = withContext(Dispatchers.IO) {
        val r = Http.get(url(kind, selected, sort, start), timeoutMs = 12_000, headers = mapOf("Referer" to "https://m.douban.com/movie/"))
        if (r.status != 200) null else parse(r.text)
    }

    fun parse(text: String): Page? = runCatching {
        val o = JSONObject(text)
        val arr = o.optJSONArray("items")
        val items = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            val it = arr!!.optJSONObject(i) ?: return@mapNotNull null
            // 广告、片单之类不是片子的条目没有评分对象也没有 card_subtitle，跳过
            val title = it.optString("title").trim()
            if (title.isEmpty() || it.optString("type") !in setOf("movie", "tv")) return@mapNotNull null
            val pic = it.optJSONObject("pic")?.let { p -> p.optString("normal").ifEmpty { p.optString("large") } }.orEmpty()
            val rating = it.optJSONObject("rating")?.optDouble("value", 0.0) ?: 0.0
            Hot.Item(title, pic, if (rating.isNaN()) 0.0 else rating, it.optString("card_subtitle").substringBefore(" / "))
        }
        val cats = o.optJSONArray("recommend_categories")
        val categories = (0 until (cats?.length() ?: 0)).mapNotNull { i ->
            val c = cats!!.optJSONObject(i) ?: return@mapNotNull null
            val data = c.optJSONArray("data") ?: return@mapNotNull null
            val options = (0 until data.length()).mapNotNull { j -> data.optJSONObject(j)?.optString("text")?.takeIf { it.isNotEmpty() } }
            val name = c.optString("type")
            when {
                options.size < 2 -> null
                // 剧集的第一组其实是「形式」（电视剧 / 综艺）：接口把它也叫「类型」，第一个选项「类型」只是占位，
                // 一个都不选时返回的是混着片单和广告的推荐流。改回「形式」，去掉占位
                options.first() == name -> Category(FORM, options.drop(1))
                else -> Category(name, options)
            }
        }
        val s = o.optJSONArray("sorts")
        val sorts = (0 until (s?.length() ?: 0)).mapNotNull { i -> s!!.optJSONObject(i)?.let { it.optString("name") to it.optString("text") } }.filter { it.first.isNotEmpty() }
        Page(items, o.optInt("total", items.size), categories, sorts, arr?.length() ?: 0)
    }.getOrNull()
}
