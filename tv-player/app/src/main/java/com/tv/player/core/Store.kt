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

    /**
     * How playback lines are ordered. "smart": test the lines first, smooth ones before sluggish ones, then sharpest
     * (skipped on metered networks to save data); "smart_always": the same, also on mobile data; "order": as listed.
     */
    fun lineMode(): String = prefs.getString("line_mode", "smart") ?: "smart"

    fun setLineMode(mode: String) = prefs.edit().putString("line_mode", mode).apply()

    /** Fetch the next segments (or pieces of a file) over extra connections on lines that limit each one. On unless switched off in the settings. */
    fun parallelPrefetch(): Boolean = prefs.getBoolean("parallel_prefetch", true)

    fun setParallelPrefetch(on: Boolean) = prefs.edit().putBoolean("parallel_prefetch", on).apply()

    /** TV only: "auto" (design size 960dp across), "small" or "large". */
    fun tvScale(): String = prefs.getString("tv_scale", "auto") ?: "auto"

    fun setTvScale(scale: String) = prefs.edit().putString("tv_scale", scale).apply()

    fun setUiMode(mode: String) = prefs.edit().putString("ui_mode", mode).apply()

    // ---- VOD sources ---------------------------------------------------------------------------------------------

    /** Sources from the subscription (or the bundled copy), plus the extra CMS sites that were verified with a real search. */
    fun subscribed(): List<VodSource> =
        parseSources(prefs.getString("config", null) ?: bundledConfig) +
            runCatching { parseSources(prefs.getString("cms", null) ?: asset("cms.json")) }.getOrDefault(emptyList()) +
            (if (jarSpiders()) runCatching { Spiders.sources(configText()) }.getOrDefault(emptyList()) else emptyList())

    fun saveCms(text: String) = prefs.edit().putString("cms", text).apply()

    /** 当前订阅的配置（没更新过时是内置的 best.json）。 */
    fun configText(): String = prefs.getString("config", null) ?: bundledConfig

    /** 是否加载订阅里的 jar 爬虫站点（第三方代码，默认关闭，见 Spiders）。 */
    fun jarSpiders(): Boolean = prefs.getBoolean("jar_spiders", false)

    fun setJarSpiders(on: Boolean) {
        prefs.edit().putBoolean("jar_spiders", on).apply()
        if (!on) Spiders.stop()
    }

    /**
     * 点播先试哪个源：cms.json 是 tvbox-check/cms_sources.py 按实测播放速度排好的（快的在前），按主机名取名次；
     * 自己添加的源排最前；cms.json 里没有的（没测过）排在测过能放的后面、实测放不出来的（`play` 为 0）前面。
     * 返回的函数按 `VodItem.sourceKey` 查。
     */
    fun playRanks(): (String) -> Int {
        val custom = customSites().map { it.key }.toSet()
        val rank = HashMap<String, Int>()
        runCatching {
            val sites = JSONObject(prefs.getString("cms", null) ?: asset("cms.json")).optJSONArray("sites") ?: JSONArray()
            for (i in 0 until sites.length()) {
                val o = sites.optJSONObject(i) ?: continue
                val dead = o.has("play") && !o.isNull("play") && o.optDouble("play", 1.0) <= 0.0
                rank.putIfAbsent(authority(o.optString("api").trim()), if (dead) UNTESTED_RANK + 1 + i else i)
            }
        }
        return { key -> if (key in custom) -1 else rank[authority(key)] ?: UNTESTED_RANK }
    }

    private const val UNTESTED_RANK = 1_000_000

    private val bundledConfig by lazy { asset("best.json") }

    /** 订阅里的 m3u8 去广告规则（见 AdFilter）。 */
    fun adRules(): List<AdFilter.Rule> = AdFilter.rules(prefs.getString("config", null) ?: bundledConfig)

    private fun authority(api: String) = runCatching { URL(api).authority.lowercase() }.getOrDefault(api)

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

    /**
     * Custom sources first, then the subscription. The same backend is often listed under several spellings
     * (…/vod, …/vod/?ac=list), so only the first entry per host is kept.
     */
    fun allSources(): List<VodSource> {
        val custom = customSites()
        val seen = custom.map { authority(it.api) }.toMutableSet()
        return custom + subscribed().filter { seen.add(authority(it.api)) }
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

    /** "auto": Douban charts, falling back to the sources' latest updates; "cms": always the sources' latest updates. */
    fun hotMode(): String = prefs.getString("hot_mode", "auto") ?: "auto"

    fun setHotMode(mode: String) = prefs.edit().putString("hot_mode", mode).apply()

    // ---- 夸克网盘 credentials (not part of backups) -------------------------------------------------------------

    fun quarkDeviceId(): String = prefs.getString("quark_device", "") ?: ""

    fun setQuarkDeviceId(id: String) = prefs.edit().putString("quark_device", id).apply()

    // ---- 加密存的设置（见 Secrets）-----------------------------------------------------------------------------

    /** 解开的值按存的密文缓存：读得很频繁（每个网盘请求都要取令牌），不必每次都过 Keystore。 */
    private val secretCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

    /** 迁移明文和写新值用同一把锁：迁移写回前确认存的还是那段明文，不会把刚刷新的令牌换回旧的。 */
    private val secretLock = Any()

    private fun secret(key: String, default: String = ""): String {
        val stored = prefs.getString(key, null) ?: return default
        secretCache[key]?.let { if (it.first == stored) return it.second }
        // 解不开时不缓存（Keystore 偶尔出错，下次再试），当作没有
        val plain = Secrets.open(stored) ?: return default
        // 旧版本存的明文：顺手加密，不用重新登录
        if (!Secrets.isSealed(stored) && stored.isNotEmpty()) {
            val sealed = Secrets.seal(stored)
            if (sealed != stored) synchronized(secretLock) { if (prefs.getString(key, null) == stored) prefs.edit().putString(key, sealed).commit() }
        }
        secretCache[key] = stored to plain
        return plain
    }

    /** 存着的密文现在解不开：别拿「没有」去覆盖它（比如网络存储列表读成空时又加一个，会把别的账号都冲掉）。 */
    private fun unreadable(key: String) = prefs.getString(key, null)?.let { Secrets.isSealed(it) && Secrets.open(it) == null } ?: false

    private fun putSecrets(vararg pairs: Pair<String, String>) = synchronized(secretLock) {
        prefs.edit().apply { pairs.forEach { (k, v) -> putString(k, Secrets.seal(v)) } }.apply()
    }

    fun quarkRefreshToken(): String = secret("quark_refresh")

    fun quarkAccessToken(): String = secret("quark_access")

    fun setQuarkTokens(refresh: String, access: String) = putSecrets("quark_refresh" to refresh, "quark_access" to access)

    // ---- 夸克网盘, web session (transferring shares needs it; the TV token cannot) --------------------------------

    fun quarkWebPus(): String = secret("quark_web_pus")

    fun quarkWebPuus(): String = secret("quark_web_puus")

    fun setQuarkWebCookies(pus: String, puus: String) = putSecrets("quark_web_pus" to pus, "quark_web_puus" to puus)

    /** "tabs": one chart per top tab as a grid; "rows": all charts stacked, each scrolling sideways. */
    fun hotLayout(): String = prefs.getString("hot_layout", "tabs") ?: "tabs"

    fun setHotLayout(mode: String) = prefs.edit().putString("hot_layout", mode).apply()

    // ---- favorites tracking --------------------------------------------------------------------------------------

    fun trackChecked(): Long = prefs.getLong("track_checked", 0L)

    fun markTrackChecked() = prefs.edit().putLong("track_checked", System.currentTimeMillis()).apply()

    // ---- playback preferences ------------------------------------------------------------------------------------

    /** Last playback speed chosen for on-demand video, applied to the next one. */
    fun playSpeed(): Float = prefs.getFloat("play_speed", 1f)

    fun setPlaySpeed(speed: Float) = prefs.edit().putFloat("play_speed", speed).apply()

    /** Seconds to skip at the start and at the end of every episode of one series (key = the series key). */
    fun skip(key: String): Pair<Int, Int> =
        prefs.getString("skip_$key", null)?.split(',')?.let { (it.getOrNull(0)?.toIntOrNull() ?: 0) to (it.getOrNull(1)?.toIntOrNull() ?: 0) } ?: (0 to 0)

    fun setSkip(key: String, intro: Int, outro: Int) =
        prefs.edit().apply { if (intro == 0 && outro == 0) remove("skip_$key") else putString("skip_$key", "$intro,$outro") }.apply()

    /** What leaving the player does: "pause" (default), "audio" keeps the sound going, "pip" shrinks to a floating window. */
    fun leaveAction(): String = prefs.getString("leave_action", "pause") ?: "pause"

    fun setLeaveAction(action: String) = prefs.edit().putString("leave_action", action).apply()

    /** The experimental AI search (see [Ai]): off until the user switches it on, because it sends the typed text to a third party. */
    fun aiSearch(): Boolean = prefs.getBoolean("ai_search", false)

    fun setAiSearch(on: Boolean) = prefs.edit().putBoolean("ai_search", on).apply()

    /** How often the "swipe for the next episode" tip in the tall full screen has been shown; dropped after a few times. */
    fun swipeTips(): Int = prefs.getInt("swipe_tips", 0)

    fun bumpSwipeTips() = prefs.edit().putInt("swipe_tips", swipeTips() + 1).apply()

    /** How often the "pull down for a tall full screen" tip has been shown; it is dropped after a few times. */
    fun vfullTips(): Int = prefs.getInt("vfull_tips", 0)

    fun bumpVfullTips() = prefs.edit().putInt("vfull_tips", vfullTips() + 1).apply()

    /** How the picture fills the frame: "fit" (letterbox, default), "zoom" (crop to fill) or "fill" (stretch). */
    fun resizeMode(): String = prefs.getString("resize_mode", "fit") ?: "fit"

    fun setResizeMode(mode: String) = prefs.edit().putString("resize_mode", mode).apply()

    /** 字幕大小：0 小、1 标准（默认）、2 大、3 特大。 */
    fun subtitleSize(): Int = prefs.getInt("subtitle_size", 1).coerceIn(0, 3)

    fun setSubtitleSize(size: Int) = prefs.edit().putInt("subtitle_size", size).apply()

    /** 电视上按片源帧率切换刷新率（见 FrameRateMatch）。默认关：不少电视切换时会黑屏一两秒。 */
    fun matchFrameRate(): Boolean = prefs.getBoolean("match_frame_rate", false)

    fun setMatchFrameRate(on: Boolean) = prefs.edit().putBoolean("match_frame_rate", on).apply()

    /** 电视上的隧道播放（解码后的画面直接交给显示硬件）。默认关：有的电视开了会黑屏或音画不同步。 */
    fun tunneling(): Boolean = prefs.getBoolean("tunneling", false)

    fun setTunneling(on: Boolean) = prefs.edit().putBoolean("tunneling", on).apply()

    /** 接收手机投屏（DLNA，见 DlnaRenderer）。没选过时电视上开、手机上关。 */
    fun dlnaReceiver(default: Boolean): Boolean = prefs.getBoolean("dlna_receiver", default)

    fun setDlnaReceiver(on: Boolean) = prefs.edit().putBoolean("dlna_receiver", on).apply()

    /** 离线下载在按流量计费的网络上也下（默认只在 Wi-Fi 下）。 */
    fun downloadOnMetered(): Boolean = prefs.getBoolean("download_metered", false)

    fun setDownloadOnMetered(on: Boolean) = prefs.edit().putBoolean("download_metered", on).apply()

    /** 网络存储（WebDAV / SMB）的列表，JSON，含账号密码：只存在本机，不进备份。 */
    fun netServersJson(): String = secret("net_servers", "[]").ifEmpty { "[]" }

    fun setNetServersJson(json: String) {
        if (unreadable("net_servers")) {
            android.util.Log.w("Store", "网络存储账号暂时读不出来，这次不保存，免得覆盖")
            return
        }
        putSecrets("net_servers" to json)
    }

    /** 投屏接收端的设备编号：手机记住的是它，所以生成一次以后不变。 */
    fun dlnaUuid(): String = prefs.getString("dlna_uuid", null) ?: java.util.UUID.randomUUID().toString().also {
        prefs.edit().putString("dlna_uuid", it).apply()
    }

    /** The newest episode count of a favorite the user has already been told about (see [Episodes]). */
    fun notifiedLatest(key: String): Int = prefs.getInt("notified_$key", 0)

    fun setNotifiedLatest(key: String, latest: Int) = prefs.edit().putInt("notified_$key", latest).apply()

    /** The line the user picked by hand for a live channel, remembered by address. */
    fun liveLine(channel: String): String? = prefs.getString("liveline_$channel", null)

    fun setLiveLine(channel: String, url: String) = prefs.edit().putString("liveline_$channel", url).apply()

    // ---- hosts whose lines recently failed to start ----------------------------------------------------------------

    private const val BAD_HOST_MS = 6 * 3600_000L
    private val badHosts: MutableMap<String, Long> by lazy {
        prefs.getStringSet("bad_hosts", emptySet())!!.mapNotNull { e ->
            e.substringAfterLast('|').toLongOrNull()?.let { e.substringBeforeLast('|') to it }
        }.toMap().toMutableMap()
    }

    private fun hostOf(url: String) = runCatching { URL(url).host.lowercase() }.getOrDefault("")

    private fun saveBadHosts() =
        prefs.edit().putStringSet("bad_hosts", badHosts.map { "${it.key}|${it.value}" }.toSet()).apply()

    fun isBadHost(url: String): Boolean = badHosts[hostOf(url)]?.let { System.currentTimeMillis() - it < BAD_HOST_MS } == true

    fun markBadHost(url: String) {
        val h = hostOf(url).ifEmpty { return }
        badHosts[h] = System.currentTimeMillis()
        saveBadHosts()
    }

    fun clearBadHost(url: String) {
        if (badHosts.remove(hostOf(url)) != null) saveBadHosts()
    }

    /** The live line that last started fine for a channel; tried first next time, but not pinned. */
    fun liveLastGood(channel: String): String? = prefs.getString("livegood_$channel", null)

    fun setLiveLastGood(channel: String, url: String) = prefs.edit().putString("livegood_$channel", url).apply()

    /** On demand: the exact stream that last played a title, for an instant "continue watching". */
    class VodLast(val src: String, val line: Int, val lineName: String, val ep: Int, val url: String, val stream: String, val time: Long)

    fun vodLast(key: String): VodLast? = prefs.getString("vodlast_$key", null)?.let {
        runCatching {
            val o = JSONObject(it)
            VodLast(o.getString("s"), o.getInt("l"), o.getString("n"), o.getInt("e"), o.getString("u"), o.getString("t"), o.getLong("at"))
        }.getOrNull()
    }

    fun setVodLast(key: String, v: VodLast) = prefs.edit().putString(
        "vodlast_$key",
        JSONObject().put("s", v.src).put("l", v.line).put("n", v.lineName).put("e", v.ep).put("u", v.url).put("t", v.stream).put("at", v.time).toString(),
    ).apply()

    /** On demand: the source key and line name that last played a title. */
    fun vodLine(key: String): Pair<String, String>? =
        prefs.getString("vodline_$key", null)?.split('\n')?.takeIf { it.size == 2 }?.let { it[0] to it[1] }

    fun setVodLine(key: String, sourceKey: String, lineName: String) =
        prefs.edit().putString("vodline_$key", "$sourceKey\n$lineName").apply()

    fun clearLiveLine(channel: String) = prefs.edit().remove("liveline_$channel").apply()

    // ---- hot chart cache -----------------------------------------------------------------------------------------

    fun hotCache(): Pair<Long, String>? = prefs.getString("hot", null)?.let { prefs.getLong("hot_at", 0L) to it }

    fun saveHot(json: String) = prefs.edit().putString("hot", json).putLong("hot_at", System.currentTimeMillis()).apply()

    // ---- search history ------------------------------------------------------------------------------------------

    fun history(): List<String> = prefs.getString("history", "")!!.split('\n').filter { it.isNotEmpty() }

    fun setHistory(list: List<String>) = prefs.edit().putString("history", list.take(12).joinToString("\n")).apply()

    fun addHistory(keyword: String) = setHistory(listOf(keyword) + history().filter { it != keyword })

    fun removeHistory(keyword: String) = setHistory(history().filter { it != keyword })

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
