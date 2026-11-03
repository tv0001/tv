package com.tv.player.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

class PlayNetStatsTest {
    private fun req(fresh: Boolean = false, dns: Long = -1, connect: Long = -1, ttfb: Long = 200, bytes: Long = 1_048_576, body: Long = 1_000, error: String? = null) =
        PlayNetStats.Req("cdn.example.com", fresh, dns, connect, ttfb, bytes, body, error)

    @Test
    fun `a request line says which step was slow`() {
        assertEquals("  cdn.example.com 新连接(域名 30ms 建连 1.5s) 首字节 200ms 1.0MB 8.4Mbps", PlayNetStats.line(req(fresh = true, dns = 30, connect = 1_500)))
        assertEquals("  cdn.example.com 首字节 200ms 1.0MB 8.4Mbps", PlayNetStats.line(req()))
        assertEquals("  cdn.example.com 失败:SocketTimeoutException", PlayNetStats.line(req(ttfb = -1, bytes = 0, body = -1, error = "SocketTimeoutException")))
    }

    @Test
    fun `the summary counts failures but not cancelled requests, and keeps only the last few`() {
        val stats = PlayNetStats()
        stats.record(req(fresh = true, dns = 20, connect = 100))
        stats.record(req(error = "取消", ttfb = -1, bytes = 0))
        stats.record(req(error = "SocketTimeoutException", ttfb = -1, bytes = 0))
        repeat(10) { stats.record(req(ttfb = 400)) }
        assertEquals(13, stats.requests)
        assertEquals(6, stats.recentText().lines().size)
        assertEquals(
            "请求 13 个（失败 1）· 新建连接 1 个，平均下载 8.4Mbps\n查域名 平均 20ms 最慢 20ms · 建连接 平均 100ms 最慢 100ms · 首字节 平均 381ms 最慢 400ms",
            stats.summary(),
        )
    }

    private fun request(url: String, range: String? = null) =
        Request.Builder().url(url).apply { if (range != null) header("Range", range) }.build()

    @Test
    fun `a stuck request shows up with the step it is waiting on, and leaves once it ends`() {
        var now = 0L
        val stats = PlayNetStats { now }
        val call = OkHttpClient().newCall(request("https://cdn.example.com/a.ts"))
        val events = stats.create(call)
        events.callStart(call)
        // 解析缓存命中：dns 事件照样来，但不算一次查询
        events.dnsStart(call, "cdn.example.com")
        events.dnsEnd(call, "cdn.example.com", listOf(InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))))
        events.connectStart(call, InetSocketAddress(0), Proxy.NO_PROXY)
        now = 300
        events.connectEnd(call, InetSocketAddress(0), Proxy.NO_PROXY, null)
        events.requestHeadersStart(call)
        now = 3_300
        assertEquals("  cdn.example.com 进行中：等首字节，已经 3.3s", stats.recentText())
        events.callFailed(call, java.net.SocketTimeoutException())
        assertEquals("  cdn.example.com 新连接(建连 300ms) 失败:SocketTimeoutException", stats.recentText())
    }

    @Test
    fun `only a whole video file read on and on is left out of the speed`() {
        assertTrue(PlayNetStats.streamedFile(request("https://a.com/movie.mp4")))
        assertTrue(PlayNetStats.streamedFile(request("https://a.com/movie.mkv", "bytes=1000-")))
        assertFalse(PlayNetStats.streamedFile(request("https://a.com/movie.mp4", "bytes=0-524287")))
        assertFalse(PlayNetStats.streamedFile(request("https://a.com/seg/1.ts")))
    }
}
