package com.tv.player.core

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpidersHeadersTest {
    private val seen = HashMap<String, Request>()

    /** 不联网：最后一个拦截器直接回响应，记下请求带了什么头。 */
    private val client = OkHttpClient.Builder()
        .addInterceptor(Spiders.headers)
        .addInterceptor(Interceptor { chain ->
            val req = chain.request()
            seen[req.url.host] = req
            val (type, body) = if (req.url.encodedPath.endsWith(".m3u8")) "application/vnd.apple.mpegurl" to "#EXTM3U\n#EXTINF:5,\nhttps://cdn-seg.example/a/1.ts\n"
            else "video/mp2t" to "x"
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody(type.toMediaType())).build()
        })
        .build()

    private fun get(url: String, ua: String = "Default-UA") = client.newCall(Request.Builder().url(url).header("User-Agent", ua).build()).execute().close()

    @Test
    fun headersFollowTheManifestToSegmentHosts() {
        Spiders.remember("https://play-host.example/v/index.m3u8", mapOf("Referer" to "https://site.example/", "User-Agent" to "Spider-UA", "Cookie" to "SESSDATA=x"))
        get("https://play-host.example/v/index.m3u8")
        assertEquals("https://site.example/", seen["play-host.example"]!!.header("Referer"))
        assertEquals("爬虫给的 UA 要盖过播放器默认的", "Spider-UA", seen["play-host.example"]!!.header("User-Agent"))
        assertEquals("SESSDATA=x", seen["play-host.example"]!!.header("Cookie"))
        // 切片在清单里另一个主机上：也要带上
        get("https://cdn-seg.example/a/1.ts")
        assertEquals("https://site.example/", seen["cdn-seg.example"]!!.header("Referer"))
        assertNull("登录 Cookie 不能跟着清单发给别的主机", seen["cdn-seg.example"]!!.header("Cookie"))
        // 无关的主机不带
        get("https://other.example/x.ts")
        assertNull(seen["other.example"]!!.header("Referer"))
        assertEquals("Default-UA", seen["other.example"]!!.header("User-Agent"))
    }
}
