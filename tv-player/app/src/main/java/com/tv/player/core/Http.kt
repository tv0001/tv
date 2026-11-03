package com.tv.player.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

object Http {
    const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    class Resp(val status: Int, val text: String, val finalUrl: String, val error: String? = null)

    /** 解析缓存 + IPv4 优先（见 CachingDns）；网络换了时由 App 清掉。 */
    val dns = CachingDns()

    /**
     * 整个应用一个连接池：测速、解析时连上的服务器，播放时直接复用那条连接，不用再握手。
     * - 只用 HTTP/1.1：HTTP/2 会把同一服务器的请求并到一条连接上，多连接预取（PrefetchScheduler、RangedDataSource）
     *   就失效了；而且连接池按「地址 + 协议」配对，播放器和测速必须是同一套设置才复用得上。
     * - 每个服务器同时最多 16 个请求（默认 5）：播放器的 OkHttp 数据源走异步请求，受这个限制，
     *   播放 + 预取 + 分段读 + 下一集预加载加起来会超过 5 个，超出的要排队。
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .dns(dns)
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = 16 })
            .build()
    }

    /** 播放器用的：和 [client] 共用连接池、DNS 和并发设置，另外挂上统计（见 PlayNetStats）。 */
    fun playerClient(events: EventListener.Factory): OkHttpClient = client.newBuilder().eventListenerFactory(events).build()

    /** Result of reading (part of) a body without keeping it, for speed tests. */
    class Fetched(val status: Int, val bytes: Long, val total: Long, val ttfbMs: Long, val readMs: Long)

    /** Reads [url] for at most [limitBytes] / [maxReadMs] and reports throughput figures. Never throws; a failure has status 0. */
    suspend fun fetchBytes(url: String, timeoutMs: Long, limitBytes: Long, maxReadMs: Long = Long.MAX_VALUE): Fetched = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).header("User-Agent", UA).build()
            val t0 = System.nanoTime()
            client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build().newCall(request).execute().use { r ->
                val ttfb = (System.nanoTime() - t0) / 1_000_000
                val body = r.body ?: return@use Fetched(r.code, 0, -1, ttfb, 0)
                val total = body.contentLength()
                var n = 0L
                val t1 = System.nanoTime()
                body.byteStream().use { s ->
                    val buf = ByteArray(64 * 1024)
                    while (n < limitBytes && (System.nanoTime() - t1) / 1_000_000 < maxReadMs) {
                        val k = s.read(buf)
                        if (k < 0) break
                        n += k
                    }
                }
                Fetched(r.code, n, total, ttfb, (System.nanoTime() - t1) / 1_000_000)
            }
        } catch (e: Exception) {
            Fetched(0, 0, -1, 0, 0)
        }
    }

    /** Never throws: network failures come back as status 0 with [Resp.error]. */
    suspend fun get(url: String, timeoutMs: Long = 10_000, limitBytes: Int = 8_000_000, headers: Map<String, String> = emptyMap()): Resp = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).header("User-Agent", UA).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build().newCall(request).execute().use { r ->
                val out = ByteArrayOutputStream()
                r.body?.byteStream()?.use { s ->
                    val buf = ByteArray(8192)
                    while (out.size() < limitBytes) {
                        val n = s.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
                Resp(r.code, out.toString(Charsets.UTF_8.name()), r.request.url.toString())
            }
        } catch (e: Exception) {
            Resp(0, "", url, e.message ?: e.javaClass.simpleName)
        }
    }
}
