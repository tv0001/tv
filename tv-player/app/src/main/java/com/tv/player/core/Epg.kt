package com.tv.player.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** What is on now and next for a live channel, from the public DIYP-style programme guide at epg.112114.xyz. */
object Epg {
    /** A programme: [start] and [end] are minutes after the start of its day [base] (Beijing time, end may pass midnight). */
    class Slot(val start: Int, val end: Int, val title: String, val base: Long = 0L) {
        val startMs get() = base + start * 60_000L
        val endMs get() = base + end * 60_000L
    }

    class Now(val current: Slot?, val next: Slot?)

    private const val FRESH_MS = 3 * 3600_000L
    private const val DAY_MS = 24 * 3600_000L
    private val china = TimeZone.getTimeZone("Asia/Shanghai")
    private val cache = HashMap<String, Pair<Long, List<Slot>>>()

    private fun minutes(text: String): Int? = text.split(':').takeIf { it.size >= 2 }?.let { (it[0].toIntOrNull() ?: return null) * 60 + (it[1].toIntOrNull() ?: return null) }

    /** The guide's date for [daysAgo] days before today, Beijing time. */
    private fun dateOf(daysAgo: Int): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = china }.format(System.currentTimeMillis() - daysAgo * DAY_MS)

    /** Midnight at the start of that day, Beijing time. */
    private fun dayStart(daysAgo: Int): Long = Calendar.getInstance(china).run {
        timeInMillis = System.currentTimeMillis() - daysAgo * DAY_MS
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    private fun nowMinutes(): Int = Calendar.getInstance(china).let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    /** "HH:mm" of a moment, Beijing time. */
    fun clock(ms: Long): String = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = china }.format(ms)

    /** "MM-dd HH:mm" of a moment, Beijing time. */
    fun stamp(ms: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.US).apply { timeZone = china }.format(ms)

    /** The programme list from memory only, so a title can be refreshed without waiting for the network. */
    fun cached(channel: String): Now? = cache[channel + dateOf(0)]?.second?.let(::pick)

    suspend fun now(channel: String): Now? {
        val slots = guide(channel, 0) ?: return null
        return pick(slots)
    }

    /** Programmes that have already aired in the last [days] days (today included), newest first. */
    suspend fun aired(channel: String, days: Int): List<Slot> {
        val nowMs = System.currentTimeMillis()
        val guides = coroutineScope { (0 until days).map { async { guide(channel, it) } }.awaitAll() }
        return guides.flatMap { it.orEmpty() }
            .filter { it.endMs <= nowMs }
            .sortedByDescending { it.startMs }
    }

    /** One day's programme list, from memory while it is fresh, else from the network (a stale copy beats nothing). */
    private suspend fun guide(channel: String, daysAgo: Int): List<Slot>? {
        val key = channel + dateOf(daysAgo)
        val hit = cache[key]
        if (hit != null && System.currentTimeMillis() - hit.first < FRESH_MS) return hit.second
        val slots = load(channel, daysAgo) ?: return hit?.second
        cache[key] = System.currentTimeMillis() to slots
        return slots
    }

    /** DIYP-style guides, tried in order; whichever answers first wins. */
    private val bases = listOf("https://epg.112114.xyz/", "http://epg.51zmt.top:8000/api/diyp/")

    private suspend fun load(channel: String, daysAgo: Int): List<Slot>? {
        val name = URLEncoder.encode(channel.replace(" ", ""), "UTF-8")
        val date = dateOf(daysAgo)
        for (base in bases) {
            val r = Http.get("$base?ch=$name&date=$date", timeoutMs = 6_000)
            if (r.status != 200) {
                android.util.Log.d("TVP", "epg $channel @ $base: HTTP ${r.status} ${r.error.orEmpty()}")
                continue
            }
            parse(r.text, dayStart(daysAgo))?.let { return it }
        }
        return null
    }

    private fun parse(text: String, dayBase: Long): List<Slot>? = try {
        val arr = JSONObject(text).optJSONArray("epg_data")
        val out = ArrayList<Slot>()
        if (arr != null) for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val start = minutes(o.optString("start")) ?: continue
            var end = minutes(o.optString("end")) ?: continue
            if (end <= start) end += 24 * 60
            val title = o.optString("title").trim()
            if (title.isNotEmpty()) out += Slot(start, end, title, dayBase)
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
