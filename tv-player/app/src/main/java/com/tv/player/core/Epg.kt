package com.tv.player.core

import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** What is on now and next for a live channel, from the public DIYP-style programme guide at epg.112114.xyz. */
object Epg {
    class Slot(val start: Int, val end: Int, val title: String)

    class Now(val current: Slot?, val next: Slot?)

    private const val FRESH_MS = 3 * 3600_000L
    private val china = TimeZone.getTimeZone("Asia/Shanghai")
    private val cache = HashMap<String, Pair<Long, List<Slot>>>()

    private fun minutes(text: String): Int? = text.split(':').takeIf { it.size >= 2 }?.let { (it[0].toIntOrNull() ?: return null) * 60 + (it[1].toIntOrNull() ?: return null) }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = china }.format(System.currentTimeMillis())

    private fun nowMinutes(): Int = Calendar.getInstance(china).let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    /** The programme list from memory only, so a title can be refreshed without waiting for the network. */
    fun cached(channel: String): Now? = cache[channel + today()]?.second?.let(::pick)

    suspend fun now(channel: String): Now? {
        val key = channel + today()
        val hit = cache[key]
        if (hit != null && System.currentTimeMillis() - hit.first < FRESH_MS) return pick(hit.second)
        val slots = load(channel) ?: return hit?.second?.let(::pick)
        cache[key] = System.currentTimeMillis() to slots
        return pick(slots)
    }

    /** DIYP-style guides, tried in order; whichever answers first wins. */
    private val bases = listOf("https://epg.112114.xyz/", "http://epg.51zmt.top:8000/api/diyp/")

    private suspend fun load(channel: String): List<Slot>? {
        val name = URLEncoder.encode(channel.replace(" ", ""), "UTF-8")
        for (base in bases) {
            val r = Http.get("$base?ch=$name&date=${today()}", timeoutMs = 6_000)
            if (r.status != 200) {
                android.util.Log.d("TVP", "epg $channel @ $base: HTTP ${r.status} ${r.error.orEmpty()}")
                continue
            }
            parse(r.text)?.let { return it }
        }
        return null
    }

    private fun parse(text: String): List<Slot>? = try {
        val arr = JSONObject(text).optJSONArray("epg_data")
        val out = ArrayList<Slot>()
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val start = minutes(o.optString("start")) ?: continue
            var end = minutes(o.optString("end")) ?: continue
            if (end <= start) end += 24 * 60
            val title = o.optString("title").trim()
            if (title.isNotEmpty()) out += Slot(start, end, title)
        }
        out.sortedBy { it.start }.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        null
    }

    private fun pick(slots: List<Slot>): Now {
        val now = nowMinutes()
        val i = slots.indexOfFirst { now >= it.start && now < it.end }
        return if (i >= 0) Now(slots[i], slots.getOrNull(i + 1)) else Now(null, slots.firstOrNull { it.start > now })
    }
}
