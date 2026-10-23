package com.tv.player.core

import android.content.Context
import com.tv.player.data.LiveChannel
import com.tv.player.data.VodSource
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

object Store {
    const val DEFAULT_BASE = "https://gh-proxy.com/https://raw.githubusercontent.com/tv0001/tv/main/tvbox-check/out/"
    private const val STALE_MS = 12 * 3600_000L

    class CustomLive(val name: String, val url: String, val text: String)

    private lateinit var app: Context
    private val prefs get() = app.getSharedPreferences("tv", Context.MODE_PRIVATE)
    private var liveRev = 0

    fun init(context: Context) {
        app = context.applicationContext
    }

    private fun asset(name: String) = app.assets.open(name).bufferedReader().use { it.readText() }

    fun baseUrl(): String = prefs.getString("base", DEFAULT_BASE) ?: DEFAULT_BASE

    fun setBaseUrl(url: String) = prefs.edit().putString("base", url).apply()

    // ---- UI mode -------------------------------------------------------------------------------------------------

    /** "auto" follows the system, "tv" / "phone" force a layout (many cheap TV boxes do not report TV mode). */
    fun uiMode(): String = prefs.getString("ui_mode", "auto") ?: "auto"

    fun setUiMode(mode: String) = prefs.edit().putString("ui_mode", mode).apply()

    // ---- VOD sources ---------------------------------------------------------------------------------------------

    /** Sources from the subscription (or the bundled copy). */
    fun subscribed(): List<VodSource> = parseSources(prefs.getString("config", null) ?: asset("best.json"))

    fun customSites(): List<VodSource> {
        val a = JSONArray(prefs.getString("custom_sites", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            VodSource(o.getString("key"), o.getString("name"), o.getString("api"))
        }
    }

    private fun saveCustomSites(list: List<VodSource>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("key", it.key).put("name", it.name).put("api", it.api)) }
        prefs.edit().putString("custom_sites", a.toString()).apply()
    }

    fun addCustomSite(name: String, api: String): VodSource {
        val clean = api.trim()
        val key = clean.trimEnd('/')
        val label = name.trim().ifEmpty { runCatching { URL(clean).host }.getOrDefault(clean) }
        val site = VodSource(key, label, clean)
        saveCustomSites(customSites().filter { it.key != key } + site)
        setEnabled(key, true)
        return site
    }

    fun removeCustomSite(key: String) {
        saveCustomSites(customSites().filter { it.key != key })
        setEnabled(key, true)
    }

    fun disabled(): Set<String> = prefs.getStringSet("disabled", emptySet())!!.toSet()

    fun setDisabled(keys: Set<String>) = prefs.edit().putStringSet("disabled", keys).apply()

    fun setEnabled(key: String, enabled: Boolean) = setDisabled(if (enabled) disabled() - key else disabled() + key)

    /** Subscribed plus custom sources, custom ones win on a duplicate address. */
    fun allSources(): List<VodSource> {
        val custom = customSites()
        val keys = custom.map { it.key }.toSet()
        return custom + subscribed().filter { it.key !in keys }
    }

    /** What searches actually use: everything that is not switched off. */
    fun sources(): List<VodSource> {
        val off = disabled()
        return allSources().filter { it.key !in off }
    }

    // ---- live ----------------------------------------------------------------------------------------------------

    fun liveText(): String = prefs.getString("live", null) ?: asset("live.txt")

    fun liveRevision() = liveRev

    fun customLives(): List<CustomLive> {
        val a = JSONArray(prefs.getString("custom_lives", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            CustomLive(o.getString("name"), o.getString("url"), o.optString("text"))
        }
    }

    private fun saveCustomLives(list: List<CustomLive>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("name", it.name).put("url", it.url).put("text", it.text)) }
        prefs.edit().putString("custom_lives", a.toString()).apply()
        liveRev++
    }

    fun addCustomLive(name: String, url: String, text: String) {
        val u = url.trim()
        val label = name.trim().ifEmpty { runCatching { URL(u).host }.getOrDefault(u) }
        saveCustomLives(customLives().filter { it.url != u } + CustomLive(label, u, text))
    }

    fun updateCustomLiveText(url: String, text: String) =
        saveCustomLives(customLives().map { if (it.url == url) CustomLive(it.name, it.url, text) else it })

    fun removeCustomLive(url: String) = saveCustomLives(customLives().filter { it.url != url })

    /** Subscribed live list merged with every custom list; channels with the same name share their lines. */
    fun channels(): List<LiveChannel> =
        Live.merge(listOf(Live.parse(liveText())) + customLives().map { Live.parse(it.text) })

    // ---- raw subscription copies, used by backup -----------------------------------------------------------------

    /** Null when the bundled copy is in use. */
    fun customConfig(): String? = prefs.getString("config", null)

    fun customLive(): String? = prefs.getString("live", null)

    fun saveConfig(text: String) = prefs.edit().putString("config", text).apply()

    fun saveLive(text: String) {
        prefs.edit().putString("live", text).apply()
        liveRev++
    }

    /** A config / live list imported by hand is "pinned": the 12-hourly auto update must not overwrite it. */
    fun isPinned(kind: String): Boolean = prefs.getBoolean("pin_$kind", false)

    fun setPinned(kind: String, pinned: Boolean) = prefs.edit().putBoolean("pin_$kind", pinned).apply()

    fun markUpdated() = prefs.edit().putLong("updated", System.currentTimeMillis()).apply()

    fun isStale(): Boolean = System.currentTimeMillis() - prefs.getLong("updated", 0L) > STALE_MS

    // ---- app update ----------------------------------------------------------------------------------------------

    fun ignoredVersion(): Int = prefs.getInt("ignored_version", 0)

    fun setIgnoredVersion(code: Int) = prefs.edit().putInt("ignored_version", code).apply()

    fun updateCheckDue(): Boolean = System.currentTimeMillis() - prefs.getLong("update_checked", 0L) > STALE_MS

    fun markUpdateChecked() = prefs.edit().putLong("update_checked", System.currentTimeMillis()).apply()

    // ---- hot chart cache -----------------------------------------------------------------------------------------

    fun hotCache(): Pair<Long, String>? = prefs.getString("hot", null)?.let { prefs.getLong("hot_at", 0L) to it }

    fun saveHot(json: String) = prefs.edit().putString("hot", json).putLong("hot_at", System.currentTimeMillis()).apply()

    // ---- search history ------------------------------------------------------------------------------------------

    fun history(): List<String> = prefs.getString("history", "")!!.split('\n').filter { it.isNotEmpty() }

    fun setHistory(list: List<String>) = prefs.edit().putString("history", list.take(12).joinToString("\n")).apply()

    fun addHistory(keyword: String) = setHistory(listOf(keyword) + history().filter { it != keyword })

    fun clearHistory() = prefs.edit().remove("history").apply()

    /** Keeps type 1 (Apple CMS JSON) sites with an http(s) api, the only kind that needs no spider runtime. */
    fun parseSources(config: String): List<VodSource> {
        val sites = JSONObject(config).optJSONArray("sites") ?: return emptyList()
        val out = LinkedHashMap<String, VodSource>()
        for (i in 0 until sites.length()) {
            val o = sites.optJSONObject(i) ?: continue
            if (o.opt("type")?.toString()?.toIntOrNull() != 1) continue
            val api = o.optString("api").trim()
            if (!api.startsWith("http://") && !api.startsWith("https://")) continue
            val key = api.trimEnd('/')
            val name = o.optString("name").substringAfterLast("• ").replace(Regex("\\[.*?]"), "").trim()
            out.getOrPut(key) { VodSource(key, name.ifEmpty { o.optString("key") }, api) }
        }
        return out.values.toList()
    }
}
