package com.tv.player.core

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * A tiny web page on the local network so a phone can type for the TV: a search word, a VOD source address or a
 * live list address, or press the remote's buttons. Only runs while its dialog is open, and every request must carry
 * the random token from the QR code.
 */
class PhoneServer(private val listener: Listener) {
    interface Listener {
        fun onSearch(keyword: String)

        /** Presses a remote button (see the names on the remote page). Returns the message shown on the phone. */
        fun onKey(name: String): String

        /** Returns the message shown on the phone. */
        suspend fun onVod(name: String, api: String): String

        suspend fun onLive(name: String, url: String): String

        /** 添加网络存储（WebDAV / SMB）：电视上输地址和密码太麻烦。返回给手机看的结果。 */
        suspend fun onStorage(kind: String, name: String, address: String, user: String, pass: String): String
    }

    val token: String = SecureRandom().let { r -> (1..8).map { "abcdefghijkmnpqrstuvwxyz23456789"[r.nextInt(32)] }.joinToString("") }
    private var socket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    val port get() = socket?.localPort ?: 0

    fun start(): Boolean {
        return try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(0))
            socket = s
            pool.execute {
                while (!s.isClosed) {
                    val c = try { s.accept() } catch (e: Exception) { break }
                    pool.execute { runCatching { serve(c) } }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun stop() {
        runCatching { socket?.close() }
        pool.shutdownNow()
    }

    /** The address a phone on the same Wi-Fi can open, or null when there is no local network. */
    fun url(): String? {
        val ip = localIpv4() ?: return null
        return "http://$ip:$port/$token/"
    }

    private fun serve(c: Socket) = c.use { sock ->
        sock.soTimeout = 10_000
        val input = sock.getInputStream()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val (method, rawPath) = lines[0].split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val length = lines.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        val body = if (length in 1..65536) String(readBytes(input, length), Charsets.UTF_8) else ""
        val path = rawPath.substringBefore('?')
        val out = sock.getOutputStream()
        if (!path.startsWith("/$token/") && path != "/$token") {
            respond(out, 404, page("不对", "<p>这个地址已失效，请在电视上重新扫码。</p>"))
            return
        }
        val action = path.removePrefix("/$token").trim('/')
        when {
            action == "remote" -> respond(out, 200, remotePage())
            action == "log" -> respond(out, 200, logPage())
            // The remote page calls these in the background; the answer is a single line of text.
            action.startsWith("key/") -> respond(out, 200, esc(listener.onKey(action.removePrefix("key/"))))
            method == "GET" || action.isEmpty() -> respond(out, 200, form())
            else -> handleForm(out, action, body)
        }
    }

    private fun handleForm(out: java.io.OutputStream, action: String, body: String) {
        val f = body.split('&').associate {
            val kv = it.split('=', limit = 2)
            URLDecoder.decode(kv[0], "UTF-8") to URLDecoder.decode(kv.getOrElse(1) { "" }, "UTF-8").trim()
        }
        val message = when (action) {
            "search" -> if (f["q"].isNullOrEmpty()) "没有输入内容" else { listener.onSearch(f["q"]!!); "已发送，电视正在搜索「${f["q"]}」" }
            "vod" -> runBlocking { listener.onVod(f["name"].orEmpty(), f["api"].orEmpty()) }
            "live" -> runBlocking { listener.onLive(f["name"].orEmpty(), f["url"].orEmpty()) }
            "storage" -> runBlocking { listener.onStorage(f["kind"].orEmpty(), f["name"].orEmpty(), f["address"].orEmpty(), f["user"].orEmpty(), f["pass"].orEmpty()) }
            else -> "未知操作"
        }
        respond(out, 200, page("结果", "<p>${esc(message)}</p><p><a href=\"/$token/\">返回继续输入</a></p>"))
    }

    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var matched = 0
        while (buf.size() < 16384) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            matched = if (b == "\r\n\r\n"[matched].code) matched + 1 else if (b == '\r'.code) 1 else 0
            if (matched == 4) return String(buf.toByteArray(), Charsets.ISO_8859_1).dropLast(4)
        }
        return null
    }

    private fun readBytes(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val k = input.read(out, read, n - read)
            if (k < 0) break
            read += k
        }
        return out.copyOf(read)
    }

    private fun respond(out: java.io.OutputStream, code: Int, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        out.write("HTTP/1.1 $code OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun page(title: String, body: String) = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>$title</title><style>body{font-family:-apple-system,sans-serif;background:#0E1015;color:#E8EAF0;margin:0;padding:16px}
h1{font-size:20px}.card{background:#161923;border-radius:14px;padding:14px;margin:14px 0}h2{font-size:15px;color:#6EA8FF;margin:0 0 8px}
input{width:100%;box-sizing:border-box;padding:12px;margin:5px 0;border-radius:10px;border:1px solid #363C50;background:#0E1015;color:#E8EAF0;font-size:16px}
button{width:100%;padding:12px;margin-top:6px;border:0;border-radius:10px;background:#6EA8FF;color:#00224D;font-size:16px;font-weight:600}a{color:#6EA8FF}p{line-height:1.6}small{color:#A4ABBD}</style></head><body><h1>$title</h1>$body</body></html>"""

    private fun remotePage() = page(
        "遥控电视",
        """<style>.pad{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}.row{display:grid;grid-template-columns:repeat(3,1fr);gap:8px;margin-top:8px}
.k{margin:0;background:#1F2330;color:#E8EAF0}.ok{background:#6EA8FF;color:#00224D}.k:active{background:#363C50}</style>
<div class="card"><h2>方向与确定</h2><div class="pad">
<span></span><button class="k" data-k="up">▲</button><span></span>
<button class="k" data-k="left">◀</button><button class="k ok" data-k="ok">确定</button><button class="k" data-k="right">▶</button>
<span></span><button class="k" data-k="down">▼</button><span></span></div></div>
<div class="card"><h2>播放与其它</h2>
<div class="row"><button class="k" data-k="back">返回</button><button class="k" data-k="menu">菜单</button><span></span></div>
<div class="row"><button class="k" data-k="rew">快退</button><button class="k" data-k="play">播放/暂停</button><button class="k" data-k="ff">快进</button></div>
<div class="row"><button class="k" data-k="ch_down">频道 −</button><button class="k" data-k="ch_up">频道 +</button><span></span></div>
<div class="row"><button class="k" data-k="vol_down">音量 −</button><button class="k" data-k="vol_up">音量 +</button><span></span></div>
<small id="msg">按一下，电视上会收到同样的遥控按键。</small></div>
<p><a href="/$token/">返回输入页</a></p>
<script>
document.querySelectorAll('[data-k]').forEach(function (b) {
  b.onclick = function () {
    var msg = document.getElementById('msg');
    fetch('key/' + b.dataset.k).then(function (r) { return r.text(); })
      .then(function (t) { msg.textContent = t; })
      .catch(function () { msg.textContent = '没连上电视，请重新扫码'; });
  };
});
</script>""",
    )

    /** 电视上记下的崩溃日志，在手机上看和复制（地址里的查询参数已经去掉）。 */
    private fun logPage() = page(
        "崩溃日志",
        "<pre style=\"white-space:pre-wrap;word-break:break-all;font-size:12px\">${esc(CrashLog.read().ifEmpty { "没有崩溃记录" })}</pre><p><a href=\"/$token/\">返回输入页</a></p>",
    )

    private fun form() = page(
        "给电视发送",
        """<div class="card"><h2>搜索</h2><form method="post" action="search"><input name="q" placeholder="片名，电视上直接开始搜索"><button>在电视上搜索</button></form></div>
<div class="card"><h2>遥控电视</h2><p>把手机当遥控器用：方向键、确定、返回、播放暂停、换台、音量。</p><a href="remote">打开遥控器</a></div>
<div class="card"><h2>添加点播源</h2><form method="post" action="vod"><input name="name" placeholder="名称（可不填）"><input name="api" placeholder="接口地址 http(s)://…/api.php/provide/vod"><button>检测并添加</button></form><small>添加前会真实搜索一次，通过才会加入。</small></div>
<div class="card"><h2>添加直播源</h2><form method="post" action="live"><input name="name" placeholder="名称（可不填）"><input name="url" placeholder="直播列表地址（TXT 或 M3U）"><button>下载并添加</button></form></div>
<div class="card"><h2>添加网络存储</h2><form method="post" action="storage"><select name="kind" style="width:100%;padding:12px;margin:5px 0;border-radius:10px;background:#0E1015;color:#E8EAF0;border:1px solid #363C50;font-size:16px"><option value="webdav">WebDAV（OpenList / Alist、群晖、威联通）</option><option value="smb">SMB（Windows 共享、NAS）</option></select><input name="address" placeholder="如 http://192.168.1.10:5244/dav 或 smb://192.168.1.10/movies"><input name="user" placeholder="用户名（没有就不填）"><input name="pass" type="password" placeholder="密码"><input name="name" placeholder="名称（可不填）"><button>连接并添加</button></form><small>密码只存在电视上，不会上传。</small></div>""" +
            CrashLog.count().let { n -> if (n == 0) "" else "<div class=\"card\"><h2>崩溃日志</h2><p>电视上记录了 $n 次崩溃。</p><a href=\"log\">查看崩溃日志</a></div>" },
    )
}

/** 本机在局域网里的 IPv4 地址（Wi-Fi / 有线优先），没有局域网时为 null。手机网页和投屏接收都用它。 */
fun localIpv4(): String? = try {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
} catch (e: Exception) {
    null
}
