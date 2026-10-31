package com.tv.player.core

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * 局域网里两台设备（手机和电视）同步收藏和观看记录。一台「等着被同步」：开一个临时 HTTP 服务，应答专用的 SSDP 搜索，
 * 屏幕上显示 4 位配对码；另一台搜到它、输入配对码，先拉对方的记录合并进来，再把合并后的推回去，两边就都齐了。
 * 配对码错 5 次服务就关掉，局域网里的别人没法随便读写。
 */
object LibrarySync {
    const val SEARCH_TARGET = "urn:tvplayer-app:service:LibrarySync:1"
    private const val GROUP = "239.255.255.250"
    private const val MAX_WRONG = 5

    class Peer(val name: String, val url: String)

    /** 被同步的一方。[onSynced] 在网络线程上调用，参数是从对方收到的（收藏，记录）条数。 */
    class Host(private val context: Context, private val name: String, private val onSynced: (Int, Int) -> Unit, private val onClosed: () -> Unit) {
        val code: String = (1000 + SecureRandom().nextInt(9000)).toString()
        @Volatile private var running = false
        private var wrong = 0
        private val gate = java.util.concurrent.Semaphore(4) // 同时最多处理几个连接：挡住开一堆连接来试配对码
        private val pool = Executors.newCachedThreadPool { r -> Thread(r, "tvp-sync").apply { isDaemon = true } }
        private var server: ServerSocket? = null
        private var ssdp: MulticastSocket? = null
        private var lock: WifiManager.MulticastLock? = null

        fun start(): Boolean {
            val s = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(0)) } }.getOrNull() ?: return false
            server = s
            running = true
            pool.execute {
                while (running) {
                    val c = try { s.accept() } catch (e: Exception) { break }
                    if (!gate.tryAcquire()) {
                        runCatching { c.close() }
                        continue
                    }
                    // 关闭的同时刚好接到连接时，线程池已经不收任务了：关掉这个连接，不能抛出去让应用崩
                    runCatching {
                        pool.execute {
                            try {
                                runCatching { c.use(::serve) }
                            } finally {
                                gate.release()
                            }
                        }
                    }.onFailure {
                        gate.release()
                        runCatching { c.close() }
                    }
                }
            }
            pool.execute { runCatching { answerSearches(s.localPort) }.onFailure { Log.d("TVP", "sync ssdp: $it") } }
            return true
        }

        fun stop() {
            if (!running) return
            running = false
            runCatching { server?.close() }
            runCatching { ssdp?.close() }
            runCatching { lock?.release() }
            pool.shutdownNow()
            onClosed()
        }

        private fun answerSearches(port: Int) {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wifi.createMulticastLock("tvplayer-sync").apply { setReferenceCounted(false); acquire() }
            val socket = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(1900))
                @Suppress("DEPRECATION") joinGroup(InetAddress.getByName(GROUP))
                soTimeout = 1_000
            }
            ssdp = socket
            val buf = ByteArray(2048)
            while (running) {
                val packet = DatagramPacket(buf, buf.size)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                val text = String(packet.data, 0, packet.length)
                if (!text.startsWith("M-SEARCH", true) || !text.contains(SEARCH_TARGET)) continue
                val ip = localIpv4() ?: continue
                val reply = searchReply("http://$ip:$port/sync", name).toByteArray()
                runCatching { socket.send(DatagramPacket(reply, reply.size, packet.socketAddress)) }
            }
        }

        private fun serve(sock: Socket) {
            sock.soTimeout = 15_000
            val input = sock.getInputStream()
            val head = readHead(input) ?: return
            val first = head.lineSequence().first().split(' ')
            val method = first.getOrElse(0) { "" }
            val target = first.getOrElse(1) { "" }
            val given = Regex("[?&]code=(\\d+)").find(target)?.groupValues?.get(1)
            val out = sock.getOutputStream()
            if (!target.startsWith("/sync")) return reply(out, 404, "{}")
            // 判断和计数放在一把锁里：并发的请求也绕不过「错 5 次就关」
            val allowed = synchronized(this) {
                when {
                    !running || wrong >= MAX_WRONG -> false
                    given == code -> true
                    else -> {
                        if (++wrong >= MAX_WRONG) pool.execute { stop() }
                        false
                    }
                }
            }
            if (!allowed) return reply(out, 403, "{\"error\":\"配对码不对\"}")
            when (method) {
                "GET" -> reply(out, 200, Library.exportJson().toString())
                "POST" -> {
                    val length = Regex("(?im)^content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    if (length !in 1..20_000_000) return reply(out, 400, "{}")
                    val body = String(readBytes(input, length), Charsets.UTF_8)
                    val (f, h) = Library.mergeJson(JSONObject(body))
                    reply(out, 200, JSONObject().put("favorites", f).put("history", h).toString())
                    onSynced(f, h)
                }
                else -> reply(out, 405, "{}")
            }
        }
    }

    // ---- 发起同步的一方 ----------------------------------------------------------------------------------------------

    /** 局域网里正在「等着被同步」的设备（2.5 秒内应答的）。 */
    suspend fun discover(context: Context): List<Peer> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("tvplayer-sync-search").apply { setReferenceCounted(false); acquire() }
        try {
            val found = LinkedHashMap<String, Peer>()
            DatagramSocket().use { socket ->
                socket.soTimeout = 400
                val msg = "M-SEARCH * HTTP/1.1\r\nHOST: $GROUP:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: $SEARCH_TARGET\r\n\r\n".toByteArray()
                repeat(2) { socket.send(DatagramPacket(msg, msg.size, InetAddress.getByName(GROUP), 1900)) }
                val end = System.currentTimeMillis() + 2_500
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < end) {
                    val packet = DatagramPacket(buf, buf.size)
                    try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                    parseReply(String(packet.data, 0, packet.length))?.let { found[it.url] = it }
                }
            }
            found.values.toList()
        } finally {
            lock.release()
        }
    }

    /** 和 [peer] 双向同步；返回给用户看的结果。 */
    suspend fun sync(peer: Peer, code: String): String = withContext(Dispatchers.IO) {
        // 局域网地址不走代理（设了全局代理的设备上，代理连不到家里的另一台）
        val client = Http.client.newBuilder().proxy(Proxy.NO_PROXY).build()
        val url = "${peer.url}?code=${code.trim()}"
        try {
            val theirs = client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (r.code == 403) return@withContext "配对码不对，请看对方屏幕上的 4 位数字"
                if (!r.isSuccessful) return@withContext "对方没有应答（HTTP ${r.code}）"
                JSONObject(r.body?.string().orEmpty())
            }
            val (gotF, gotH) = Library.mergeJson(theirs)
            val body = Library.exportJson().toString().toRequestBody("application/json".toMediaType())
            val sent = client.newCall(Request.Builder().url(url).post(body).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext "已收到对方的记录，但发回去失败（HTTP ${r.code}）"
                JSONObject(r.body?.string().orEmpty())
            }
            "同步完成：这台新增或更新了 $gotF 条收藏、$gotH 条观看记录；${peer.name} 新增或更新了 ${sent.optInt("favorites")} 条收藏、${sent.optInt("history")} 条观看记录"
        } catch (e: Exception) {
            "连不上对方：${e.message ?: e.javaClass.simpleName}"
        }
    }

    // ---- 报文（单测覆盖） --------------------------------------------------------------------------------------------

    fun searchReply(location: String, name: String) =
        "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=60\r\nEXT:\r\nLOCATION: $location\r\nST: $SEARCH_TARGET\r\n" +
            "X-TVP-NAME: ${URLEncoder.encode(name, "UTF-8")}\r\nUSN: $location::$SEARCH_TARGET\r\n\r\n"

    fun parseReply(text: String): Peer? {
        if (!text.contains(SEARCH_TARGET)) return null
        val location = Regex("(?im)^LOCATION:\\s*(\\S+)").find(text)?.groupValues?.get(1) ?: return null
        val name = Regex("(?im)^X-TVP-NAME:\\s*(\\S+)").find(text)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") } ?: location
        return Peer(name, location)
    }

    private fun reply(out: OutputStream, code: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        out.write("HTTP/1.1 $code X\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun readHead(input: InputStream): String? {
        val buf = java.io.ByteArrayOutputStream()
        var tail = 0
        while (buf.size() < 16_384) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            tail = (tail shl 8 or b) and 0xFFFFFFFF.toInt()
            if (tail == 0x0D0A0D0A) return buf.toString(Charsets.UTF_8.name())
        }
        return null
    }

    private fun readBytes(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(out, read, n - read)
            if (r < 0) break
            read += r
        }
        return out
    }
}
