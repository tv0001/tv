package com.tv.player.core

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * 网页嗅探（照 TVBox / FongMi 的 Sniffer）：解析页、官方视频页里的真实视频地址是页面脚本算出来的，只能在不显示的 WebView 里
 * 把页面跑起来，看它请求了什么，第一个像视频的请求就是要的地址（连同它带的 Referer / UA）。
 * 页面是第三方的：不给它任何 JS 接口、不让它读本地文件，证书出错按默认拒绝；同一时间只开一个 WebView。
 */
object Sniffer {
    private const val TAG = "Sniffer"
    private val video = Regex("https?://[^\\s]{12,}\\.(?:m3u8|mp4|mkv|flv|mp3|m4a|aac|mpd)|https?://.*?video/tos[^\\s]*")
    private val gate = Semaphore(1)

    class Hit(val url: String, val headers: Map<String, String>)

    /** 配置里 rules 的一条：hosts 匹配时，regex 里的视为视频、exclude 里的排除（都按「包含」判断，和 FongMi 一样）。 */
    class Rule(val hosts: List<String>, val regex: List<String>, val exclude: List<String>)

    fun rules(config: String): List<Rule> = runCatching {
        val a = JSONObject(config).optJSONArray("rules") ?: JSONArray()
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            fun list(k: String) = o.optJSONArray(k)?.let { x -> (0 until x.length()).map { x.optString(it) } }.orEmpty()
            Rule(list("hosts"), list("regex"), list("exclude"))
        }
    }.getOrDefault(emptyList())

    /** 照 FongMi 的 isVideoFormat：规则先，再排除解析页本身（url=http…、.html），最后看扩展名。 */
    fun isVideo(url: String, page: String, rules: List<Rule>): Boolean {
        val hosts = listOf(page, url).mapNotNull { runCatching { java.net.URL(it).host }.getOrNull() }
        rules.firstOrNull { r -> r.hosts.any { h -> hosts.any { it.contains(h.removePrefix(".*").removePrefix("*")) } } }?.let { r ->
            if (r.exclude.any { url.contains(it) }) return false
            if (r.regex.any { url.contains(it) }) return true
        }
        val path = url.substringBefore('?').substringBefore('#')
        if (url.contains("url=http") || url.contains("v=http") || path.contains(".html")) return false
        return video.containsMatchIn(url)
    }

    suspend fun sniff(context: Context, page: String, headers: Map<String, String>, rules: List<Rule>, timeoutMs: Long = 15_000): Hit? = gate.withPermit {
        val found = CompletableDeferred<Hit>()
        var web: WebView? = null
        try {
            withContext(Dispatchers.Main) { web = create(context, page, headers, rules, found) }
            withTimeoutOrNull(timeoutMs) { found.await() }.also { Log.d(TAG, "${if (it != null) "found" else "nothing"} on ${page.take(80)}") }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 没有 WebView（有的盒子没装或版本太老）、页面打不开
            Log.w(TAG, "sniff failed", e)
            null
        } finally {
            web?.let { w -> Handler(Looper.getMainLooper()).post { runCatching { w.stopLoading(); w.destroy() } } }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(context: Context, page: String, headers: Map<String, String>, rules: List<Rule>, found: CompletableDeferred<Hit>): WebView {
        val w = WebView(context.applicationContext)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            blockNetworkImage = true
            allowFileAccess = false
            allowContentAccess = false
            headers.entries.firstOrNull { it.key.equals("User-Agent", true) }?.let { userAgentString = it.value }
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                if (!found.isCompleted && isVideo(url, page, rules)) {
                    val sent = request.requestHeaders.filterKeys { k -> listOf("Referer", "User-Agent", "Origin", "Cookie").any { it.equals(k, true) } }
                    found.complete(Hit(url, sent))
                }
                return null
            }
        }
        w.loadUrl(page, headers)
        return w
    }
}
