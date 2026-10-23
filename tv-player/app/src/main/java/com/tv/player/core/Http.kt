package com.tv.player.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

object Http {
    const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    class Resp(val status: Int, val text: String, val finalUrl: String, val error: String? = null)

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

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
