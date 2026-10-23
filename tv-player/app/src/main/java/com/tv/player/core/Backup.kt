package com.tv.player.core

import org.json.JSONArray
import org.json.JSONObject

object Backup {
    fun export(): String = JSONObject().apply {
        put("version", 2)
        put("base", Store.baseUrl())
        Store.customConfig()?.let { put("config", it) }
        Store.customLive()?.let { put("live", it) }
        put("customSites", JSONArray().also { a -> Store.customSites().forEach { a.put(JSONObject().put("name", it.name).put("api", it.api)) } })
        put("customLives", JSONArray().also { a ->
            Store.customLives().forEach { a.put(JSONObject().put("name", it.name).put("url", it.url).put("text", it.text)) }
        })
        put("disabled", JSONArray(Store.disabled().toList()))
        put("searchHistory", JSONArray(Store.history()))
        put("library", Library.exportJson())
    }.toString(2)

    /** Returns a one-line summary of what was restored; throws if the file is not a backup. */
    fun restore(text: String): String {
        val o = JSONObject(text)
        require(o.has("version")) { "不是本 App 导出的备份文件" }
        val parts = ArrayList<String>()
        o.optString("base").takeIf { it.isNotEmpty() }?.let { Store.setBaseUrl(it) }
        o.optString("config").takeIf { it.isNotEmpty() }?.let {
            Store.saveConfig(it)
            parts += "订阅点播源 ${Store.parseSources(it).size} 个"
        }
        o.optString("live").takeIf { it.isNotEmpty() }?.let {
            Store.saveLive(it)
            parts += "订阅直播频道 ${Live.parse(it).size} 个"
        }
        o.optJSONArray("customSites")?.let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let { Store.addCustomSite(it.optString("name"), it.getString("api")) }
            if (a.length() > 0) parts += "自定义点播源 ${a.length()} 个"
        }
        o.optJSONArray("customLives")?.let { a ->
            for (i in 0 until a.length()) a.getJSONObject(i).let { Store.addCustomLive(it.optString("name"), it.getString("url"), it.optString("text")) }
            if (a.length() > 0) parts += "自定义直播 ${a.length()} 个"
        }
        o.optJSONArray("disabled")?.let { a -> Store.setDisabled((0 until a.length()).map { a.getString(it) }.toSet()) }
        o.optJSONArray("searchHistory")?.let { a -> Store.setHistory((0 until a.length()).map { a.getString(it) }) }
        o.optJSONObject("library")?.let {
            val (f, h) = Library.importJson(it)
            parts += "收藏 $f 条、历史 $h 条"
        }
        return if (parts.isEmpty()) "备份里没有可恢复的内容" else "已恢复：" + parts.joinToString("，")
    }
}
