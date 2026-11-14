package com.tv.player.core

import android.content.Context
import android.util.Log
import androidx.media3.common.MimeTypes
import com.tv.player.data.Episode
import com.tv.player.data.PlayLine
import com.tv.player.data.VodDetail
import com.tv.player.data.VodItem
import com.tv.player.data.VodSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelChildren
import okhttp3.Interceptor
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections

/**
 * TVBox jar 爬虫（type 3、api 是 `csp_Xxx` 的站点）。默认关闭，在设置里打开。
 *
 * 爬虫是第三方代码，跑在单独的爬虫进程里（见 SpiderHost / SpiderService），这里只拿它返回的 JSON 来解析；
 * 主进程不加载 jar。两个进程仍是同一个应用身份（能读同样的文件），所以网盘登录等另外加密存（见 Secrets）。
 *
 * 站点在搜索列表里是 `VodSource(key = "csp:站点key", api = 同上)`；选集地址是 `spider://站点key/线路名/选集id`，
 * 播放前由 [Resolve.stream] 交给 [play] 调 playerContent 换成真实地址。播放结果的处理照 FongMi（TVBox）的约定：
 * 真实地址 = playUrl 前缀 + url（url 也可能是 [清晰度, 地址, …]）；header 给播放请求（站点配置里的 header 兜底）；
 * format 定媒体类型；parse / jx 为 1 时地址是网页，要再解析。
 */
object Spiders {
    private const val PREFIX = "csp:"
    private const val SCHEME = "spider://"
    private const val TAG = "Spiders"
    private const val PLAY_TTL_MS = 60_000L

    /** 不加载的爬虫：Config 的详情会关掉宿主里类名含 Video / Detail 的页面。 */
    private val blocked = setOf("Config")

    /** [jar] 是站点自己的 jar（配置里站点的 "jar" 字段），空时用配置的总 jar。 */
    class Site(val key: String, val name: String, val cls: String, val ext: String, val header: Map<String, String>, val playUrl: String, val jar: String = "")

    /** 配置里的解析接口：type 1 是 JSON 接口（直接请求，回 {url}），type 0 是网页（要嗅探）；ext.flag 限定只解析哪些线路。 */
    private class Parse(val name: String, val type: Int, val url: String, val header: Map<String, String>, val flags: List<String>)

    private class Config(val sites: List<Site>, val flags: List<String>, val jar: String, val parses: List<Parse> = emptyList(), val rules: List<Sniffer.Rule> = emptyList())

    private class Played(val url: String, val at: Long)


    /**
     * 爬虫调用是阻塞的、取消不了：放到这里跑，调用方超时就先返回，爬虫在后台跑完了事（不然外面的 withTimeout 形同虚设）。
     * 单独限 16 个并发：一次搜索会同时问几十个站点，不能把应用其他网络请求用的 IO 线程占满。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(16))

    /** 选集地址 → 换到的真实地址。线路测速和正式播放都会取一次，不重复调爬虫。 */
    private val played = Collections.synchronizedMap(object : LinkedHashMap<String, Played>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Played>?) = size > 200
    })

    /** 主机 → 爬虫要求的请求头（见 [headers]）。 */
    private val hostHeaders = Collections.synchronizedMap(object : LinkedHashMap<String, Map<String, String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, String>>?) = size > 64
    })

    /** 真实地址 → 媒体类型（DASH，或地址里看不出来的 HLS）。 */
    private val mimes = Collections.synchronizedMap(object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 200
    })

    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
        SpiderClient.init(context)
    }

    fun isSource(source: VodSource) = source.api.startsWith(PREFIX)

    fun isLink(url: String) = url.startsWith(SCHEME)

    /** [play] 换到的地址的媒体类型；null 时按地址判断。 */
    fun mimeFor(url: String): String? = mimes[url]

    // ---- 配置 ----------------------------------------------------------------------------------------------------

    @Volatile private var parsed: Pair<String, Config>? = null

    /** 每次搜索每个站点都会查，配置没变时用上次的结果。 */
    private fun config(text: String = Store.configText()): Config {
        parsed?.let { if (it.first == text) return it.second }
        return parseConfig(text).also { parsed = text to it }
    }

    /** 配置里能搜索的 jar 爬虫站点（用总 jar 的和自带 jar 的都算）。 */
    private fun parseConfig(text: String): Config {
        val root = JSONObject(text)
        val flags = root.optJSONArray("flags")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val sites = root.optJSONArray("sites")
        val jar = root.optString("spider").trim()
        if (sites == null) return Config(emptyList(), flags, jar)
        val out = ArrayList<Site>()
        for (i in 0 until sites.length()) {
            val o = sites.optJSONObject(i) ?: continue
            if (o.opt("type")?.toString() != "3") continue
            val api = o.optString("api").trim()
            if (!api.startsWith("csp_") || api.removePrefix("csp_") in blocked) continue
            if (o.optInt("searchable", 1) == 0) continue
            // 站点自己的 jar；相对地址（./xx.jar）没法定位就跳过（订阅里的已由 tvbox-check 补成完整地址）
            val own = o.optString("jar").trim()
            if (own.isNotEmpty() && !own.startsWith("http")) continue
            if (own.isEmpty() && !jar.startsWith("http")) continue
            val key = o.optString("key").trim()
            if (key.isEmpty()) continue
            val name = o.optString("name").replace(Regex("\\[.*?]"), "").trim().ifEmpty { key }
            out += Site(key, name, api.removePrefix("csp_"), o.opt("ext")?.toString().orEmpty(), headerMap(o.opt("header")), o.optString("playUrl").trim(), own)
        }
        val parses = root.optJSONArray("parses")?.let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { p ->
                val url = p.optString("url").trim()
                if (!url.startsWith("http")) return@mapNotNull null
                val ext = p.optJSONObject("ext")
                val pf = ext?.optJSONArray("flag")?.let { f -> (0 until f.length()).map { f.optString(it) } }.orEmpty()
                Parse(p.optString("name"), p.opt("type")?.toString()?.toIntOrNull() ?: 0, url, headerMap(ext?.opt("header")), pf)
            }
        }.orEmpty()
        return Config(out, flags, jar, parses, Sniffer.rules(text))
    }

    fun sources(configText: String): List<VodSource> = config(configText).sites.map { VodSource(PREFIX + it.key, it.name, PREFIX + it.key) }

    private fun site(key: String): Site? = config().sites.firstOrNull { it.key == key }

    private fun siteOf(source: VodSource) = site(source.key.removePrefix(PREFIX)) ?: throw IllegalStateException("订阅里没有这个站点")

    /** header 可能是对象，也可能是 JSON 字符串。 */
    private fun headerMap(v: Any?): Map<String, String> {
        val o = when (v) {
            is JSONObject -> v
            is String -> runCatching { JSONObject(v) }.getOrNull()
            else -> null
        } ?: return emptyMap()
        // OkHttp 遇到不合法的头名 / 头值会抛异常（在播放器的下载线程里就是崩溃），先滤掉
        return o.keys().asSequence().associateWith { o.optString(it) }
            .filter { (k, v) -> v.isNotEmpty() && k.isNotEmpty() && k.all { it in '!'..'~' && it != ':' } && v.all { it == '\t' || it in ' '..'~' } }
    }

    /** jar 缺类、缺方法时抛的是 Error（NoSuchMethodError 等），上层只接 Exception，这里转成 Exception，不让应用崩。 */
    private suspend fun <T> call(block: suspend () -> T): T = scope.async {
        try {
            block()
        } catch (e: Exception) {
            throw e
        } catch (e: Throwable) {
            throw IllegalStateException("爬虫出错：$e", e)
        }
    }.await()

    /** 在爬虫进程里调爬虫（见 SpiderHost），拿回它的原始 JSON。 */
    private suspend fun remote(site: Site, method: String, vararg args: String): String = call { SpiderClient.call(site.jar.ifEmpty { config().jar }, site, method, *args) }

    /** 关掉开关：断开爬虫进程（它随之退出），清掉记下的请求头等。 */
    fun stop() {
        SpiderClient.stop()
        clear()
    }

    // ---- 搜索 / 详情 / 播放 -------------------------------------------------------------------------------------

    suspend fun search(source: VodSource, keyword: String): List<VodItem> {
        val site = siteOf(source)
        val text = remote(site, "search", keyword)
        val list = JSONObject(text.ifBlank { "{}" }).optJSONArray("list") ?: return emptyList()
        val out = ArrayList<VodItem>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val id = o.optString("vod_id").trim()
            val title = o.optString("vod_name").trim()
            if (id.isEmpty() || title.isEmpty() || Cms.adult.containsMatchIn(o.optString("type_name"))) continue
            out += VodItem(
                source.key, source.name, id, title, o.optString("vod_pic").trim(), o.optString("vod_remarks").trim(),
                year = o.optString("vod_year").trim().takeIf { it.length == 4 && it.all(Char::isDigit) }.orEmpty(),
                kind = Cms.kindOf(o.optString("type_name")),
            )
        }
        return out
    }

    suspend fun detail(source: VodSource, id: String): VodDetail? {
        val site = siteOf(source)
        val o = JSONObject(remote(site, "detail", id).ifBlank { "{}" }).optJSONArray("list")?.optJSONObject(0) ?: return null
        fun field(name: String) = o.optString(name).trim().takeUnless { it == "null" }.orEmpty()
        val froms = field("vod_play_from").split("\$\$\$")
        val groups = field("vod_play_url").split("\$\$\$")
        val lines = ArrayList<PlayLine>()
        for (i in 0 until minOf(froms.size, groups.size)) {
            val flag = froms[i].trim()
            val eps = ArrayList<Episode>()
            for (seg in groups[i].split('#')) {
                val ep = seg.substringAfter('$').trim()
                if (ep.isEmpty()) continue
                val name = seg.substringBefore('$', "").trim()
                eps += Episode(name.ifEmpty { "第${eps.size + 1}集" }, SCHEME + enc(site.key) + "/" + enc(flag) + "/" + enc(ep))
            }
            if (eps.isNotEmpty()) lines += PlayLine(flag.ifEmpty { "线路${lines.size + 1}" }, eps)
        }
        // 网盘资源站没有网盘登录时给不出线路：自己从站点详情页找夸克分享（见 PanShares）
        if (lines.isEmpty()) lines += panLines(site, id)
        if (lines.isEmpty()) return null
        return VodDetail(
            field("vod_name"), field("vod_pic"), lines,
            year = field("vod_year").takeIf { it.length == 4 }.orEmpty(),
            area = field("vod_area"), type = field("type_name"), remarks = field("vod_remarks"),
            director = field("vod_director"), actor = field("vod_actor"),
            content = field("vod_content").replace(Regex("<[^>]+>"), "").trim(),
        )
    }

    /** ext 里的站点地址（"site" 可能是数组或字符串）+ 详情 id（站内路径）就是详情页；里面的夸克分享交给 PanShares。 */
    private suspend fun panLines(site: Site, id: String): List<PlayLine> {
        if (!id.startsWith("/") && !id.startsWith("http")) return emptyList()
        val ext = runCatching { JSONObject(site.ext) }.getOrNull() ?: return emptyList()
        val bases = ext.optJSONArray("site")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: listOfNotNull(ext.optString("site").takeIf { it.startsWith("http") })
        for (base in (if (id.startsWith("http")) listOf("") else bases).take(2)) {
            val r = Http.get(if (base.isEmpty()) id else base.trimEnd('/') + id, timeoutMs = 10_000)
            if (r.status != 200) continue
            val shares = PanShares.findShares(r.text)
            if (shares.isNotEmpty()) return PanShares.lines(shares)
        }
        return emptyList()
    }

    /** 调 playerContent 换真实地址（一分钟内同一集不重复调）。 */
    suspend fun play(link: String): String? {
        played[link]?.let { if (System.currentTimeMillis() - it.at < PLAY_TTL_MS) return it.url }
        SpiderProxy.bypassLocal() // 播放器要连爬虫进程开的 127.0.0.1 本地代理
        val parts = link.removePrefix(SCHEME).split('/', limit = 3)
        if (parts.size < 3) return null
        val site = site(dec(parts[0])) ?: return null
        val flag = dec(parts[1])
        val text = runCatching { withTimeout(20_000) { remote(site, "player", flag, dec(parts[2]), JSONArray(config().flags).toString()) } }
            .onFailure { Log.w(TAG, "playerContent ${site.cls}", it) }.getOrNull() ?: return null
        val r = runCatching { JSONObject(text) }.getOrNull() ?: return null
        // url 也可能是 ["清晰度名", "地址", …]：取第一个地址
        val raw = r.opt("url")
        val url = (if (raw is JSONArray) raw.optString(1) else raw?.toString().orEmpty()).trim()
        if (url.isEmpty()) return null
        // header / headers 两种写法都有（TVBoxOS 两个都读）
        val headers = (headerMap(r.opt("header")) + headerMap(r.opt("headers"))).ifEmpty { site.header }
        val real = r.optString("playUrl").ifEmpty { site.playUrl } + url
        remember(real, headers)
        val format = r.optString("format")
        // parse / jx 为 1 时地址可能是网页：TVBox 先看它本身像不像视频（嗅探器的规则），不像才用解析接口或 WebView 嗅探；
        // 这里不像时从网页里找视频地址（没有 WebView 嗅探）
        if (!real.startsWith("http")) return null // magnet: 之类本应用放不了
        val jx = r.optInt("jx") == 1
        val needParse = r.optInt("parse") == 1 || jx
        val mime = call { mimeOf(format, real) } // 也会读开头几个字节：网页地址实际返回的是 m3u8 时同样算视频
        val stream = if (!needParse || mime != null || looksLikeVideo(real)) real
        else withTimeoutOrNull(45_000) { parse(real, flag, headers, jx || (r.optString("playUrl").isEmpty() && flag in config().flags)) } ?: return null
        // 解析出来的地址，要带的头在解析时已经记下（嗅探到的 Referer 等），不能再用 jar 给的盖掉
        if (stream == real) remember(stream, headers)
        (if (stream == real) mime else call { mimeOf(format, stream) })?.let { mimes[stream] = it }
        played[link] = Played(stream, System.currentTimeMillis())
        return stream
    }

    /**
     * 网页地址换成视频地址（照 FongMi）：要用解析接口时（jx，或线路在配置的 flags 里），先并发问 JSON 接口、谁先回用谁，
     * 再挨个嗅探网页解析接口（最多三个）；都不行就嗅探网页本身，最后才从网页源码里找。嗅探到的请求头记下给播放用。
     */
    private suspend fun parse(page: String, flag: String, headers: Map<String, String>, useParses: Boolean): String? {
        val cfg = config()
        if (useParses) {
            val usable = cfg.parses.filter { it.flags.isEmpty() || flag in it.flags }
            firstJson(usable.filter { it.type == 1 }, page)?.let { return it }
            for (p in usable.filter { it.type == 0 }.take(3)) {
                Sniffer.sniff(app, p.url + page, p.header, cfg.rules)?.let { hit -> remember(hit.url, hit.headers); return hit.url }
            }
        }
        Sniffer.sniff(app, page, headers, cfg.rules)?.let { hit -> remember(hit.url, headers + hit.headers); return hit.url }
        return Resolve.stream(page)
    }

    /** 几个 JSON 接口同时问，谁先给出地址用谁；都不行（或 12 秒内没有）时 null。 */
    private suspend fun firstJson(parses: List<Parse>, page: String): String? {
        if (parses.isEmpty()) return null
        return kotlinx.coroutines.coroutineScope {
            val first = kotlinx.coroutines.CompletableDeferred<String?>()
            val jobs = parses.map { p -> launch { runCatching { jsonParse(p, page) }.getOrNull()?.let { first.complete(it) } } }
            launch { jobs.joinAll(); first.complete(null) }
            kotlinx.coroutines.withTimeoutOrNull(12_000) { first.await() }.also { coroutineContext.cancelChildren() }
        }
    }

    /** JSON 解析接口：GET 接口地址 + 网页地址，回的 JSON 里 url 是视频地址（可能还带 header）。 */
    private suspend fun jsonParse(p: Parse, page: String): String? {
        val r = Http.get(p.url + page, timeoutMs = 10_000, headers = p.header)
        if (r.status != 200) return null
        val o = runCatching { JSONObject(r.text) }.getOrNull() ?: return null
        val url = (o.optJSONObject("data")?.optString("url") ?: o.optString("url")).trim()
        if (!url.startsWith("http") || url == page) return null
        remember(url, headerMap(o.opt("header")).ifEmpty { p.header })
        Log.d(TAG, "parsed by ${p.name}")
        return url
    }

    /** TVBox 嗅探器认作视频的地址：路径或参数里带视频扩展名。 */
    private fun looksLikeVideo(url: String) = Regex("\\.(m3u8|mp4|mkv|flv|mpd)|m3u8|video/tos", RegexOption.IGNORE_CASE).containsMatchIn(url.substringAfter("://").substringAfter('/'))

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")

    // ---- 请求头和媒体类型 -------------------------------------------------------------------------------------------

    private fun hostOf(url: String) = runCatching { URL(url).host.lowercase() }.getOrNull()

    /**
     * 记下这个地址的主机要带的请求头。没给请求头时不动（后台测速别的线路时不能把正在放的线路要的头删掉）。
     * 本应用自己登录的主机（夸克网盘、网络存储）不接受：jar 不能借这个改写本应用发往那里的请求（比如换掉 Cookie）。
     */
    internal fun remember(url: String, headers: Map<String, String>) {
        val host = hostOf(url) ?: return
        if (headers.isEmpty() || ownHost(host, url)) return
        hostHeaders[host] = headers
    }

    private fun ownHost(host: String, url: String) =
        host.endsWith("quark.cn") || host.endsWith("extscreen.com") || runCatching { QuarkWeb.headersFor(host) != null || NetStore.headersFor(url) != null }.getOrDefault(false)

    /** 关掉开关时清掉，之后的请求不再补头。 */
    fun clear() {
        hostHeaders.clear()
        mimes.clear()
        played.clear()
    }

    /** format 给了就用；地址里看得出来的交给播放器自己判断；看不出来的读开头几个字节。 */
    private fun mimeOf(format: String, url: String): String? {
        val f = format.lowercase()
        when {
            "dash" in f || "mpd" in f -> return MimeTypes.APPLICATION_MPD
            "mpegurl" in f || "m3u8" in f -> return MimeTypes.APPLICATION_M3U8
        }
        val u = url.lowercase()
        if (".m3u8" in u) return null
        if (".mpd" in u || "type=mpd" in u) return MimeTypes.APPLICATION_MPD
        if (Regex("\\.(mp4|mkv|flv|avi|mov|ts|m4a|mp3)(\\?|$)").containsMatchIn(u)) return null
        val head = runCatching {
            Http.client.newCall(Request.Builder().url(url).header("User-Agent", Http.UA).header("Range", "bytes=0-2047").build()).execute().use { resp ->
                resp.body?.source()?.let { s -> s.request(2048); s.buffer.snapshot().utf8() }.orEmpty()
            }
        }.getOrDefault("")
        return when {
            head.trimStart().startsWith("#EXTM3U") -> MimeTypes.APPLICATION_M3U8
            "<MPD" in head -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    /**
     * 给爬虫地址补请求头（装在 Http.client 上，播放器、线路测速都走它）。TVBox 是整个播放项都带这些请求头；
     * 这里按主机记，清单（m3u8 / mpd）里出现的其他主机（切片常在别的 CDN 上）也记上同样的请求头。
     */
    val headers = Interceptor { chain ->
        val req = chain.request()
        val extra = hostHeaders[req.url.host.lowercase()] ?: return@Interceptor chain.proceed(req)
        val resp = chain.proceed(req.newBuilder().apply { extra.forEach { (k, v) -> header(k, v) } }.build())
        // 清单里的地址、跳转后的地址都可能在别的主机上；带过去的头不含 Cookie / Authorization，不把登录信息发给第三方主机
        val shared = extra.filterKeys { !it.equals("Cookie", true) && !it.equals("Authorization", true) }
        val finalHost = resp.request.url.host.lowercase()
        if (finalHost != req.url.host.lowercase() && !ownHost(finalHost, resp.request.url.toString())) hostHeaders.putIfAbsent(finalHost, shared)
        val type = resp.header("Content-Type").orEmpty().lowercase()
        val path = resp.request.url.encodedPath.lowercase()
        val small = resp.body?.contentLength()?.let { it in 0..2_000_000 } ?: false
        // 只读清单（m3u8 / mpd），视频切片不碰：路径里带 m3u8 的切片（/m3u8/…/1.ts）也不能读
        val manifest = "mpegurl" in type || "dash+xml" in type || path.endsWith(".m3u8") || path.endsWith(".mpd") ||
            (small && ("text" in type || "xml" in type))
        if (manifest && !type.startsWith("video") && "octet" !in type) {
            runCatching { resp.peekBody(2_000_000).string() }.getOrNull()?.let { body ->
                Regex("https?://([^/\\s\"'<>:]+)").findAll(body).map { it.groupValues[1].lowercase() }.toSet()
                    .filterNot { ownHost(it, "https://$it/") }.forEach { hostHeaders.putIfAbsent(it, shared) }
            }
        }
        resp
    }
}
