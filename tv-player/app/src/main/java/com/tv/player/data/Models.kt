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
)

data class Episode(val name: String, val url: String)

data class PlayLine(val name: String, val episodes: List<Episode>)

data class VodDetail(val title: String, val pic: String, val lines: List<PlayLine>)

data class LiveChannel(val name: String, val group: String, val urls: List<String>)

/** Same title from one or more sources. */
data class VodGroup(val key: String, val title: String, val pic: String, val remarks: String, val items: List<VodItem>)

fun List<VodItem>.toJsonString(): String = JSONArray().also { arr ->
    for (i in this) arr.put(
        JSONObject()
            .put("sk", i.sourceKey).put("sn", i.sourceName).put("id", i.id)
            .put("t", i.title).put("p", i.pic).put("r", i.remarks),
    )
}.toString()

fun vodItemsFromJson(text: String): List<VodItem> {
    val arr = JSONArray(text)
    return (0 until arr.length()).map {
        val o = arr.getJSONObject(it)
        VodItem(o.getString("sk"), o.getString("sn"), o.getString("id"), o.getString("t"), o.optString("p"), o.optString("r"))
    }
}
