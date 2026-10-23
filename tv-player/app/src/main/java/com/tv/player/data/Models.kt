package com.tv.player.data

import org.json.JSONArray
import org.json.JSONObject

data class VodSource(val key: String, val name: String, val api: String)

data class VodItem(
    val sourceKey: String,
    val sourceName: String,
    val id: String,
    val title: String,
    val pic: String,
    val remarks: String,
    val year: String = "",
    val kind: String = "",
)

data class Episode(val name: String, val url: String)

data class PlayLine(val name: String, val episodes: List<Episode>)

data class VodDetail(
    val title: String,
    val pic: String,
    val lines: List<PlayLine>,
    val year: String = "",
    val area: String = "",
    val type: String = "",
    val remarks: String = "",
    val director: String = "",
    val actor: String = "",
    val content: String = "",
    val score: String = "",
)

/** A channel can play back its past programmes: [template] is the source's address pattern, [days] how far back. */
data class Catchup(val template: String, val days: Int)

data class LiveChannel(val name: String, val group: String, val urls: List<String>, val catchup: Catchup? = null)

/** Same title from one or more sources. */
data class VodGroup(val key: String, val title: String, val pic: String, val remarks: String, val items: List<VodItem>, val badge: String = "")

fun List<VodItem>.toJsonString(): String = JSONArray().also { arr ->
    for (i in this) arr.put(
        JSONObject()
            .put("sk", i.sourceKey).put("sn", i.sourceName).put("id", i.id)
            .put("t", i.title).put("p", i.pic).put("r", i.remarks).put("y", i.year).put("k", i.kind),
    )
}.toString()

fun vodItemsFromJson(text: String): List<VodItem> {
    val arr = JSONArray(text)
    return (0 until arr.length()).map {
        val o = arr.getJSONObject(it)
        VodItem(o.getString("sk"), o.getString("sn"), o.getString("id"), o.getString("t"), o.optString("p"), o.optString("r"), o.optString("y"), o.optString("k"))
    }
}
