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
