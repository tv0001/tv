package com.tv.player.core

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Locale

/**
 * 一次播放里播放器发出的请求（含预取、分段读、下一集预加载）的耗时统计，给播放日志用：
 * 卡顿时看最近几个请求和还没结束的请求慢在哪一步（查域名、建连接、等首字节、下载），退出时出一份小结。
 * 回调在 OkHttp 的各个线程上，读写都加锁。
 */
class PlayNetStats(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) : EventListener.Factory {
    /**
     * 一个请求：dnsMs / connectMs 为 -1 表示没有这一步（复用了连接、解析有缓存）。
     * bodyMs 为 -1 表示不拿来算速度：没收到响应，或是整个视频文件的流式读取（播放器缓冲满了会停下不读，
     * 这段空闲也会算进耗时，速度就被低估了）。
     */
    class Req(
        val host: String,
        val fresh: Boolean,
        val dnsMs: Long,
        val connectMs: Long,
        val ttfbMs: Long,
        val bytes: Long,
        val bodyMs: Long,
        val error: String?,
    )

    /** 还没结束的请求：卡在哪一步、从什么时候开始。 */
    private class Pending(val host: String, val start: Long) {
        @Volatile
        var stage = "排队"
    }

    private val recent = ArrayDeque<Req>()
    private val pending = LinkedHashSet<Pending>()
    private var calls = 0
    private var failed = 0
    private var fresh = 0
    private val dns = Agg()
    private val connect = Agg()
    private val ttfb = Agg()
    private var bytes = 0L
    private var bodyMs = 0L

    private class Agg {
        var n = 0
        var sum = 0L
        var max = 0L
        fun add(v: Long) {
            n++
            sum += v
            if (v > max) max = v
        }

        fun text() = if (n == 0) "无" else "平均 ${sum / n}ms 最慢 ${ms(max)}"
    }

    override fun create(call: Call): EventListener = object : EventListener() {
        val start = clock()
        val now = Pending(call.request().url.host, start)
        var dnsAt = -1L
        var dnsMs = -1L
        var connectAt = -1L
        var connectMs = -1L
        var ttfbMs = -1L
        var headersAt = -1L
        var body = 0L

        // 真正发出去才算进行中（只建了没执行的 Call 不会有结束事件）
        override fun callStart(call: Call) {
            synchronized(this@PlayNetStats) { pending += now }
        }

        override fun dnsStart(call: Call, domainName: String) {
            dnsAt = clock()
            now.stage = "查域名"
        }

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            // 解析缓存（CachingDns）命中时这两个事件照样会来，耗时约 0：不算一次查询
            if (dnsAt >= 0) dnsMs = (clock() - dnsAt).takeIf { it > CACHED_DNS_MS } ?: -1
        }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            if (connectAt < 0) connectAt = clock() // 一个地址连不上换下一个：从第一次开始算
            now.stage = "建连接"
        }

        // 含 TLS 握手：connectEnd 在握手完成之后才来
        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            connectMs = clock() - connectAt
        }

        override fun requestHeadersStart(call: Call) {
            now.stage = "等首字节"
        }

        override fun responseHeadersEnd(call: Call, response: Response) {
            headersAt = clock()
            ttfbMs = headersAt - start
            now.stage = "下载"
        }

        override fun responseBodyEnd(call: Call, byteCount: Long) {
            body = byteCount
        }

        override fun callEnd(call: Call) = done(call, null)

        // 播放器换线、跳转时取消的请求也走 callFailed：记成「取消」，不算失败
        override fun callFailed(call: Call, ioe: IOException) = done(call, ioe.javaClass.simpleName)

        fun done(call: Call, error: String?) {
            val end = clock()
            val req = Req(
                host = now.host,
                fresh = connectAt >= 0,
                dnsMs = dnsMs,
                connectMs = connectMs,
                ttfbMs = ttfbMs,
                bytes = body,
                bodyMs = if (headersAt >= 0 && !streamedFile(call.request())) end - headersAt else -1,
                error = error?.let { if (call.isCanceled()) "取消" else it },
            )
            synchronized(this@PlayNetStats) { pending -= now }
            record(req)
        }
    }

    fun record(req: Req) = synchronized(this) {
        calls++
        if (req.error != null && req.error != "取消") failed++
        if (req.fresh) fresh++
        if (req.dnsMs >= 0) dns.add(req.dnsMs)
        if (req.connectMs >= 0) connect.add(req.connectMs)
        if (req.ttfbMs >= 0) ttfb.add(req.ttfbMs)
        // 太小的（播放列表、取消掉的）算速度不准
        if (req.bytes >= MIN_SPEED_BYTES && req.bodyMs > 0) {
            bytes += req.bytes
            bodyMs += req.bodyMs
        }
        recent.addLast(req)
        if (recent.size > RECENT) recent.removeFirst()
    }

    val requests: Int get() = synchronized(this) { calls }

    /** 卡顿时：最近结束的几个请求，再加上还没结束的（卡住的多半就是它），一行一个。 */
    fun recentText(): String = synchronized(this) {
        val now = clock()
        val done = recent.map { line(it) }
        val open = pending.map { "  ${it.host} 进行中：${it.stage}，已经 ${ms(now - it.start)}" }
        (done + open).ifEmpty { listOf("  （还没有请求）") }.joinToString("\n")
    }

    /** 退出时：这次播放的全部请求。 */
    fun summary(): String = synchronized(this) {
        val speed = if (bodyMs > 0) "，平均下载 ${mbps(bytes, bodyMs)}" else ""
        "请求 $calls 个（失败 $failed）· 新建连接 $fresh 个$speed\n" +
            "查域名 ${dns.text()} · 建连接 ${connect.text()} · 首字节 ${ttfb.text()}"
    }

    companion object {
        private const val RECENT = 6
        private const val MIN_SPEED_BYTES = 64 * 1024L
        private const val CACHED_DNS_MS = 1L

        /**
         * 整个视频文件从某处一直读下去（不带结束位置）：播放器缓冲满了会停下不读，连接却开着。
         * 分段读（RangedDataSource）带结束位置，HLS 分片不是视频文件，都照常算速度。
         */
        fun streamedFile(request: Request): Boolean {
            if (!PrefetchPolicy.rangedFile(request.url.encodedPath)) return false
            val range = request.header("Range") ?: return true
            return range.trimEnd().endsWith("-")
        }

        fun line(r: Req): String = buildString {
            append("  ").append(r.host)
            if (r.fresh) {
                append(" 新连接(")
                if (r.dnsMs >= 0) append("域名 ${ms(r.dnsMs)} ")
                append("建连 ${ms(r.connectMs)})")
            }
            if (r.ttfbMs >= 0) append(" 首字节 ${ms(r.ttfbMs)}")
            if (r.bytes > 0) append(" ${size(r.bytes)}")
            if (r.bytes >= MIN_SPEED_BYTES && r.bodyMs > 0) append(" ${mbps(r.bytes, r.bodyMs)}")
            r.error?.let { append(" 失败:").append(it) }
        }

        fun ms(v: Long) = if (v >= 1_000) String.format(Locale.US, "%.1fs", v / 1000.0) else "${v}ms"

        fun size(b: Long) = if (b >= 1_048_576) String.format(Locale.US, "%.1fMB", b / 1_048_576.0) else "${b / 1024}KB"

        fun mbps(b: Long, ms: Long) = String.format(Locale.US, "%.1fMbps", b * 8.0 / ms / 1000.0)
    }
}
