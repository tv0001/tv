package com.tv.player.core

import com.tv.player.data.Episode
import com.tv.player.data.PlayLine
import com.tv.player.data.VodDetail
import com.tv.player.data.VodItem
import com.tv.player.data.VodSource
import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder

/** Apple CMS JSON API: ?ac=videolist&wd= for search, ?ac=detail&ids= for detail. */
object Cms {
    /** Category names that are kept out of every list; "擦边" marks the suggestive corner of the short-drama category. */
    val adult = Regex("伦理|福利|情色|成人|写真|里番|三级|擦边")

    /** Coarse category from a source's own category name; "" when it cannot be told. */
    fun kindOf(typeName: String): String = when {
        "短剧" in typeName -> "短剧" // before the "剧" suffix rule, which would file it under series
        "综艺" in typeName -> "综艺"
        "动漫" in typeName || "动画" in typeName -> "动漫"
        typeName.endsWith("剧") || "连续剧" in typeName || "电视剧" in typeName -> "剧集"
        typeName.endsWith("片") || "电影" in typeName -> "电影"
        else -> ""
    }

    // The tag must stand on its own (after a space or in brackets): "Shanghai" ends in "ai" and is not tagged.
    private val aiMark = Regex("(?:^|[\\s（(])[\\s（(]*(?:AI|Ai|ai)(?:漫剧|动漫)?\\s*[)）]?\\s*$")

    /**
     * Short-drama titles often carry an "AI" / "Ai漫剧" tag at the end, and some sources glue an alternative title on
     * with "$" (their own separator): neither is part of the name, and both break searching.
     */
    fun cleanTitle(name: String): String =
        name.substringBefore('$').trim().replace(aiMark, "").replace(Regex("\\s{2,}"), " ").trim().ifEmpty { name.trim() }

    private fun url(api: String, query: String) = api + (if ('?' in api) "&" else "?") + query

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun search(source: VodSource, keyword: String): List<VodItem> {
        val r = Http.get(url(source.api, "ac=videolist&wd=${enc(keyword)}&pg=1"))
        if (r.status != 200) throw IllegalStateException(r.error ?: "HTTP ${r.status}")
        val root = try {
            JSONObject(r.text)
        } catch (e: JSONException) {
            throw IllegalStateException("返回的不是 JSON（可能不支持搜索，或被拦截）")
        }
        val list = root.optJSONArray("list") ?: throw IllegalStateException("返回里没有 list 字段")
        val out = ArrayList<VodItem>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val id = o.optString("vod_id").trim()
            val title = o.optString("vod_name").trim()
            if (id.isEmpty() || title.isEmpty()) continue
            if (adult.containsMatchIn(o.optString("type_name"))) continue
            out += VodItem(
                source.key, source.name, id, title, o.optString("vod_pic").trim(), o.optString("vod_remarks").trim(),
                year = o.optString("vod_year").trim().takeIf { it.length == 4 && it.all(Char::isDigit) }.orEmpty(),
                kind = kindOf(o.optString("type_name")),
            )
        }
        return out
    }

    suspend fun detail(source: VodSource, id: String): VodDetail? {
        val r = Http.get(url(source.api, "ac=detail&ids=${enc(id)}"))
        if (r.status != 200) return null
        val o = JSONObject(r.text).optJSONArray("list")?.optJSONObject(0) ?: return null
        val lines = parseLines(o.optString("vod_play_from"), o.optString("vod_play_url"))
        if (lines.isEmpty()) return null
        fun field(name: String) = o.optString(name).trim().takeUnless { it == "null" }.orEmpty()
        return VodDetail(
            field("vod_name"), field("vod_pic"), lines,
            year = field("vod_year").takeIf { it.length == 4 }.orEmpty(),
            area = field("vod_area"),
            type = field("type_name"),
            remarks = field("vod_remarks"),
            director = field("vod_director"),
            actor = field("vod_actor"),
            content = plainText(field("vod_content")),
            score = (field("vod_douban_score").ifEmpty { field("vod_score") }).takeIf { (it.toDoubleOrNull() ?: 0.0) > 0 }.orEmpty(),
        )
    }

    /** Synopses come as HTML fragments; the details panel wants one plain paragraph. */
    private fun plainText(html: String): String =
        html.replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex("\\s+"), " ").trim()

    /** `from` and `urls` hold one entry per line, joined by $$$; episodes are joined by #, each `name$url`. */
    fun parseLines(from: String, urls: String): List<PlayLine> {
        val names = from.split("\$\$\$")
        val groups = urls.split("\$\$\$")
        val out = ArrayList<PlayLine>()
        for (i in 0 until minOf(names.size, groups.size)) {
            val episodes = ArrayList<Episode>()
            for (seg in groups[i].split('#')) {
                val dollar = seg.indexOf('$')
                val url = (if (dollar >= 0) seg.substring(dollar + 1) else seg).trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) continue
                val name = (if (dollar >= 0) seg.substring(0, dollar) else "").trim()
                episodes += Episode(name.ifEmpty { "第${episodes.size + 1}集" }, url)
            }
            if (episodes.isNotEmpty()) out += PlayLine(names[i].trim().ifEmpty { "线路${out.size + 1}" }, episodes)
        }
        return out
    }
}
