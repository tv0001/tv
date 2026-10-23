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
    private val adult = Regex("伦理|福利|情色|成人|写真|里番|三级")

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
            out += VodItem(source.key, source.name, id, title, o.optString("vod_pic").trim(), o.optString("vod_remarks").trim())
        }
        return out
    }

    suspend fun detail(source: VodSource, id: String): VodDetail? {
        val r = Http.get(url(source.api, "ac=detail&ids=${enc(id)}"))
        if (r.status != 200) return null
        val o = JSONObject(r.text).optJSONArray("list")?.optJSONObject(0) ?: return null
        val lines = parseLines(o.optString("vod_play_from"), o.optString("vod_play_url"))
        if (lines.isEmpty()) return null
        return VodDetail(o.optString("vod_name"), o.optString("vod_pic"), lines)
    }

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
