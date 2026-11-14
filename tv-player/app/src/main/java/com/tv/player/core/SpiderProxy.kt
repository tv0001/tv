package com.tv.player.core

import android.util.Log
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * jar 爬虫要的本地代理（FongMi / TVBox 宿主都有）：爬虫启动时会挨个问 127.0.0.1:9978…10000 的 `/proxy?do=ck`，
 * 回 `ok` 的那个端口就当成宿主代理，弹幕、B 站之类的 m3u8 改写都走它。没有它时爬虫会把这些端口都试一遍，
 * 每次取播放地址要多等一分钟。
 *
 * 只监听 127.0.0.1；`do=ck` 直接回 ok，其余请求交给 jar 里的静态方法 `com.github.catvod.spider.Proxy.proxy(Map)`（有几个 jar 时见 SpiderHost.proxy），
 * 它返回 `[状态码, Content-Type, 内容(InputStream / byte[] / String), 响应头(可选)]`。
 */
object SpiderProxy {
    private const val TAG = "SpiderProxy"
    @Volatile private var server: ServerSocket? = null
    @Volatile var port = -1
        private set


    @Synchronized
    fun start() {
        bypassLocal()
        if (server != null) return
        val loopback = InetAddress.getByName("127.0.0.1")
        for (p in 9978..10000) {
            val s = runCatching { ServerSocket(p, 16, loopback) }.getOrNull() ?: continue
            server = s
            port = p
            thread(name = "spider-proxy", isDaemon = true) { accept(s) }
            Log.i(TAG, "listening on $p")
            return
        }
        Log.w(TAG, "9978–10000 都被占用，本地代理没起来")
    }

    /**
     * 系统设了 HTTP 代理时（盒子上配了代理、模拟器转到宿主机），爬虫发往 127.0.0.1 的请求也会被交给代理，
     * 到不了这里。把本机地址加进本进程的不走代理名单；系统改代理时会重写这个属性，所以每次用爬虫前都补一次。
     */
    fun bypassLocal() {
        val cur = System.getProperty("http.nonProxyHosts").orEmpty()
        if ("127.0.0.1" !in cur) System.setProperty("http.nonProxyHosts", listOf(cur, "127.0.0.1", "localhost").filter { it.isNotEmpty() }.joinToString("|"))
    }

    private fun accept(s: ServerSocket) {
        while (!s.isClosed) {
            val c = runCatching { s.accept() }.getOrNull() ?: run { Thread.sleep(200); null } ?: continue
            thread(isDaemon = true) { runCatching { handle(c) }.onFailure { Log.d(TAG, "request failed", it) }; runCatching { c.close() } }
        }
    }

    private fun handle(c: Socket) {
        c.soTimeout = 30_000
        val input = c.getInputStream().bufferedReader()
        val requestLine = input.readLine() ?: return
        val target = requestLine.split(' ').getOrNull(1) ?: return
        val params = LinkedHashMap<String, String>()
        target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.forEach {
            params[URLDecoder.decode(it.substringBefore('='), "UTF-8")] = URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }
        val reqHeaders = LinkedHashMap<String, String>()
        while (true) {
            val h = input.readLine()
            if (h.isNullOrEmpty()) break
            val colon = h.indexOf(':')
            if (colon > 0) reqHeaders[h.substring(0, colon).trim().lowercase()] = h.substring(colon + 1).trim()
        }
        if (!allowed(reqHeaders, port)) return respond(c, 403, "text/plain", "forbidden".byteInputStream(), emptyMap())
        // 请求头也并进参数（FongMi 的代理就是这样，头名小写），爬虫靠它拿 Range 等
        params.putAll(reqHeaders)
        if (params["do"] == "ck") return respond(c, 200, "text/plain", "ok".byteInputStream(), emptyMap())
        val r = SpiderHost.proxy(params) ?: return respond(c, 404, "text/plain", "".byteInputStream(), emptyMap())
        val body: InputStream = when (val b = r.getOrNull(2)) {
            is InputStream -> b
            is ByteArray -> ByteArrayInputStream(b)
            null -> ByteArrayInputStream(ByteArray(0))
            else -> b.toString().byteInputStream()
        }
        @Suppress("UNCHECKED_CAST")
        val headers = (r.getOrNull(3) as? Map<String, Any?>).orEmpty()
        respond(c, (r.getOrNull(0) as? Number)?.toInt() ?: 200, r.getOrNull(1)?.toString() ?: "application/octet-stream", body, headers)
    }

    /**
     * 只接本机程序直接发来的请求：带 Origin 的是网页里的脚本发的（别的网站借浏览器来调本地代理），Host 不是本机的是 DNS 重绑定。
     * jar 和播放器（OkHttp）都不带 Origin、Host 是 127.0.0.1:端口。同一台设备上别的应用仍然能调，和 TVBox 一样——jar 拼代理地址时
     * 只用端口，加不了口令。
     */
    internal fun allowed(headers: Map<String, String>, port: Int): Boolean {
        if (headers.keys.any { it.equals("origin", true) }) return false
        val host = headers.entries.firstOrNull { it.key.equals("host", true) }?.value?.trim()?.lowercase() ?: return true
        return host in setOf("127.0.0.1", "localhost", "127.0.0.1:$port", "localhost:$port")
    }

    private fun respond(c: Socket, code: Int, type: String, body: InputStream, headers: Map<String, Any?>) {
        val out = c.getOutputStream().buffered()
        val head = StringBuilder("HTTP/1.1 $code ${if (code in 200..299) "OK" else "Error"}\r\nContent-Type: $type\r\nConnection: close\r\n")
        // 长度和分块方式由这里定（读到流结束、关连接）：jar 给的 Content-Length / Transfer-Encoding 不转发，
        // 和实际写出的内容对不上时播放器会读错位置（FongMi 也是这样过滤的）
        val skip = setOf("content-type", "connection", "content-length", "transfer-encoding")
        headers.forEach { (k, v) -> if (v != null && k.lowercase() !in skip) head.append("$k: $v\r\n") }
        head.append("\r\n")
        out.write(head.toString().toByteArray())
        body.use { it.copyTo(out) }
        out.flush()
    }
}
