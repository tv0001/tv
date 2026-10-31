package com.tv.player.core

import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.util.Log
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * DLNA 接收端：应用在前台时，在局域网里以 MediaRenderer（「TV Player（型号）」）出现，手机上支持 DLNA 投屏的视频 App
 * 可以把视频投过来。HTTP 服务（描述文件、SOAP 控制、事件订阅）和 SSDP（应答搜索、定时通告）都自己实现，不依赖第三方库；
 * 报文的拼装和解析在 DlnaProtocol 里。
 */
object DlnaRenderer {
    /** 真正的播放由界面层实现（见 ui.CastSession）。这些方法在网络线程上调用，实现要自己切回主线程。 */
    interface Target {
        /** 应用不在前台时返回 false：后台既打不开页面，也不该出声。 */
        fun open(url: String, title: String): Boolean
        fun play()
        fun pause()
        fun stop()
        fun seek(ms: Long)

        /** 现在的播放状态，随时可读。 */
        fun snapshot(): Snapshot
    }

    /** [state] 用 UPnP 的说法：NO_MEDIA_PRESENT、STOPPED、PLAYING、PAUSED_PLAYBACK、TRANSITIONING。 */
    class Snapshot(val state: String, val positionMs: Long, val durationMs: Long, val url: String)

    private const val GROUP = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val ALIVE_EVERY_MS = 60_000L
    private const val SINK = "http-get:*:video/mp4:*,http-get:*:video/x-matroska:*,http-get:*:video/mp2t:*," +
        "http-get:*:application/vnd.apple.mpegurl:*,http-get:*:application/x-mpegURL:*,http-get:*:video/x-flv:*," +
        "http-get:*:audio/mpeg:*,http-get:*:audio/mp4:*,http-get:*:*:*"

    private lateinit var app: Context
    private lateinit var target: Target
    private var uuid = ""
    private var name = ""
    @Volatile private var running = false
    // 每次开启加一：快速「关→开」时，上一轮还没退出的线程看到编号变了就自己结束
    @Volatile private var session = 0
    @Volatile private var metadata = "" // 投过来时带的 DIDL-Lite，查询进度时原样还回去
    @Volatile private var ssdp: MulticastSocket? = null
    private var pool: ExecutorService? = null
    private var http: ServerSocket? = null

    /** 订阅了事件的手机 App：订阅号 → (回调地址, 服务, 序号)。 */
    private class Sub(val callback: String, val service: String, var seq: Long = 0)
    private val subs = java.util.concurrent.ConcurrentHashMap<String, Sub>()
    private val events by lazy { Http.client.newBuilder().proxy(java.net.Proxy.NO_PROXY).callTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build() }

    val isRunning get() = running

    private fun alive(mine: Int) = running && session == mine

    @Synchronized
    fun start(context: Context, target: Target, uuid: String, name: String) {
        if (running) return
        app = context.applicationContext
        this.target = target
        this.uuid = uuid
        this.name = name
        running = true
        val mine = ++session
        val p = Executors.newCachedThreadPool { r -> Thread(r, "tvp-dlna").apply { isDaemon = true } }
        pool = p
        p.execute { runCatching { serveHttp(mine) }.onFailure { Log.d("TVP", "dlna http: $it") } }
        p.execute { runCatching { serveSsdp(mine) }.onFailure { Log.d("TVP", "dlna ssdp: $it") } }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        session++
        val s = ssdp
        val location = location()
        pool?.execute {
            // 下线通告：手机上的投屏列表会马上去掉这台
            if (s != null && location != null) runCatching { targets().forEach { t -> send(s, DlnaProtocol.notify("ssdp:byebye", t, uuid, location)) } }
        }
        runCatching { http?.close() }
        http = null
        subs.clear()
        pool?.shutdown()
        pool = null
    }

    private fun location(): String? {
        val port = http?.localPort ?: return null
        val ip = localIpv4() ?: return null
        return "http://$ip:$port/dmr/desc.xml"
    }

    private fun targets() = listOf("upnp:rootdevice", "uuid:$uuid", DlnaProtocol.DEVICE_TYPE) + DlnaProtocol.SERVICES.map(DlnaProtocol::serviceType)

    // ---- SSDP：应答 M-SEARCH，定时发上线通告 ---------------------------------------------------------------------

    /**
     * 组播绑在局域网那块网卡上（开着 VPN / 代理时默认路由是 tun，不绑就收不到）；还没连上局域网、出错、换了网，
     * 过一会儿重来，不会悄悄失效。组播锁在这个线程里拿、在这里放，开关再快也不会漏放。
     */
    private fun serveSsdp(mine: Int) {
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("tvplayer-dlna-renderer").apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            val group = InetAddress.getByName(GROUP)
            while (alive(mine)) {
                val ip = localIpv4()
                if (ip == null) {
                    Thread.sleep(3_000)
                    continue
                }
                val iface = runCatching { java.net.NetworkInterface.getByInetAddress(InetAddress.getByName(ip)) }.getOrNull()
                val socket = try {
                    MulticastSocket(null).apply {
                        reuseAddress = true
                        bind(InetSocketAddress(SSDP_PORT))
                        if (iface != null) {
                            networkInterface = iface
                            joinGroup(InetSocketAddress(group, SSDP_PORT), iface)
                        } else {
                            @Suppress("DEPRECATION") joinGroup(group)
                        }
                        soTimeout = 1_000
                        timeToLive = 4
                    }
                } catch (e: Exception) {
                    Log.d("TVP", "dlna ssdp join: $e")
                    Thread.sleep(5_000)
                    continue
                }
                ssdp = socket
                try {
                    loopSsdp(socket, mine, ip)
                } catch (e: Exception) {
                    if (alive(mine)) Log.d("TVP", "dlna ssdp: $e")
                } finally {
                    runCatching { socket.close() }
                }
                if (alive(mine)) Thread.sleep(1_000)
            }
        } finally {
            runCatching { lock.release() }
        }
    }

    /** 收发 SSDP，直到关掉或者本机地址变了（换了网）。 */
    private fun loopSsdp(socket: MulticastSocket, mine: Int, ip: String) {
        // HTTP 服务起来以后才知道地址
        while (alive(mine) && location() == null) Thread.sleep(100)
        var lastAlive = 0L
        var lastIpCheck = System.currentTimeMillis()
        val buf = ByteArray(4096)
        while (alive(mine)) {
            val now = System.currentTimeMillis()
            if (now - lastIpCheck > 10_000) {
                lastIpCheck = now
                if (localIpv4() != ip) return
            }
            if (now - lastAlive > ALIVE_EVERY_MS) {
                lastAlive = now
                location()?.let { loc -> targets().forEach { t -> send(socket, DlnaProtocol.notify("ssdp:alive", t, uuid, loc)) } }
            }
            val packet = DatagramPacket(buf, buf.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            }
            val text = String(packet.data, 0, packet.length)
            if (!text.startsWith("M-SEARCH", ignoreCase = true)) continue
            val st = Regex("(?im)^ST:\\s*(.+?)\\s*$").find(text)?.groupValues?.get(1) ?: continue
            val loc = location() ?: continue
            DlnaProtocol.searchTargets(st, uuid).forEach { t ->
                val reply = DlnaProtocol.searchResponse(t, uuid, loc).toByteArray()
                runCatching { socket.send(DatagramPacket(reply, reply.size, packet.socketAddress)) }
            }
        }
    }

    private fun send(socket: MulticastSocket, text: String) {
        val bytes = text.toByteArray()
        runCatching { socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(GROUP), SSDP_PORT)) }
    }

    // ---- 事件 ----------------------------------------------------------------------------------------------------

    /** 播放状态变了（CastSession 发现的）：告诉订阅了 AVTransport 的手机。 */
    fun stateChanged() {
        if (!running) return
        pool?.execute { subs.forEach { (sid, sub) -> if (sub.service == "AVTransport") notifyOne(sid, sub) } }
    }

    private fun notifyOne(sid: String, sub: Sub) {
        val change = if (sub.service == "RenderingControl") DlnaProtocol.lastChangeRcs(volume(), audio().isStreamMute(AudioManager.STREAM_MUSIC))
        else target.snapshot().let { DlnaProtocol.lastChangeAvt(it.state, it.url, it.durationMs) }
        val seq = synchronized(sub) { sub.seq++ }
        val request = okhttp3.Request.Builder().url(sub.callback)
            .method("NOTIFY", DlnaProtocol.propertySet(change).toByteArray().toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .header("NT", "upnp:event").header("NTS", "upnp:propchange").header("SID", sid).header("SEQ", seq.toString())
            .build()
        runCatching { events.newCall(request).execute().close() }.onFailure { subs.remove(sid) } // 回调地址不通了就不再发
    }

    // ---- HTTP：描述文件、SOAP 控制、事件订阅 ------------------------------------------------------------------------

    private fun serveHttp(mine: Int) {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(0))
        // 和 stop() 用同一把锁：刚绑好就被关了的话，这个 socket 由这里关，也不能把新一轮的 http 覆盖掉
        val p = synchronized(this) {
            if (!alive(mine)) {
                runCatching { s.close() }
                return
            }
            http = s
            pool
        }
        Log.d("TVP", "dlna renderer on port ${s.localPort}: $name")
        try {
            while (alive(mine) && !s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                runCatching { p?.execute { runCatching { c.use(::serve) } } }.onFailure { runCatching { c.close() } }
            }
        } finally {
            runCatching { s.close() }
        }
    }

    private fun serve(sock: Socket) {
        sock.soTimeout = 10_000
        val input = sock.getInputStream()
        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val parts = lines[0].split(' ')
        val method = parts.getOrElse(0) { "" }.uppercase()
        val path = parts.getOrElse(1) { "" }.substringBefore('?')
        val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length in 1..262_144) String(readBytes(input, length), Charsets.UTF_8) else ""
        val out = sock.getOutputStream()
        val service = Regex("^/dmr/(\\w+)").find(path)?.groupValues?.get(1)
        when {
            method == "GET" && path == "/dmr/desc.xml" -> reply(out, 200, DlnaProtocol.description(uuid, name))
            method == "GET" && path.endsWith(".xml") && service != null -> DlnaProtocol.scpd(service)?.let { reply(out, 200, it) } ?: reply(out, 404, "")
            method == "POST" && path.endsWith("/control") && service != null -> control(out, service, headers["soapaction"], body)
            method == "SUBSCRIBE" -> subscribe(out, service, headers)
            method == "UNSUBSCRIBE" -> {
                headers["sid"]?.let { subs.remove(it) }
                reply(out, 200, "")
            }
            else -> reply(out, 404, "")
        }
    }

    /** 新订阅（带 CALLBACK）记下回调地址并马上发一次现在的状态；续订（只带 SID）原样回。 */
    private fun subscribe(out: OutputStream, service: String?, headers: Map<String, String>) {
        val renew = headers["sid"]
        if (renew != null) {
            if (subs.containsKey(renew)) reply(out, 200, "", listOf("SID" to renew, "TIMEOUT" to "Second-1800"))
            else reply(out, 412, "")
            return
        }
        val callback = DlnaProtocol.callback(headers["callback"])
        if (service == null || callback == null) {
            reply(out, 412, "")
            return
        }
        val sid = "uuid:${UUID.randomUUID()}"
        val sub = Sub(callback, service)
        subs[sid] = sub
        reply(out, 200, "", listOf("SID" to sid, "TIMEOUT" to "Second-1800"))
        pool?.execute { notifyOne(sid, sub) }
    }

    private fun control(out: OutputStream, service: String, soapAction: String?, body: String) {
        val action = DlnaProtocol.action(soapAction, body)
        if (action == null || !DlnaProtocol.knows(service, action)) {
            reply(out, 500, DlnaProtocol.fault(401, "Invalid Action"))
            return
        }
        Log.d("TVP", "dlna $service.$action")
        val values: List<Pair<String, String>> = when (action) {
            "SetAVTransportURI" -> {
                val url = DlnaProtocol.arg(body, "CurrentURI")
                if (url == null) {
                    reply(out, 500, DlnaProtocol.fault(402, "Invalid Args"))
                    return
                }
                // 只放网络地址：局域网里谁都能发这个命令，不能让它打开本机的 file:// 或 content://
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                    reply(out, 500, DlnaProtocol.fault(714, "Illegal MIME-type"))
                    return
                }
                val meta = DlnaProtocol.arg(body, "CurrentURIMetaData").orEmpty()
                if (!target.open(url, DlnaProtocol.title(meta) ?: "手机投屏")) {
                    reply(out, 500, DlnaProtocol.fault(701, "TV Player is not on screen"))
                    return
                }
                metadata = meta
                emptyList()
            }
            "Play" -> {
                if (target.snapshot().state == "NO_MEDIA_PRESENT") {
                    reply(out, 500, DlnaProtocol.fault(701, "Transition not available"))
                    return
                }
                target.play()
                emptyList()
            }
            "Pause" -> emptyList<Pair<String, String>>().also { target.pause() }
            "Stop" -> emptyList<Pair<String, String>>().also { target.stop() }
            "Seek" -> {
                val unit = DlnaProtocol.arg(body, "Unit").orEmpty()
                val ms = DlnaProtocol.arg(body, "Target")?.let(DlnaProtocol::parseTime)
                if (ms == null || (unit != "REL_TIME" && unit != "ABS_TIME")) {
                    reply(out, 500, DlnaProtocol.fault(710, "Seek mode not supported"))
                    return
                }
                target.seek(ms)
                emptyList()
            }
            "GetTransportInfo" -> listOf("CurrentTransportState" to target.snapshot().state, "CurrentTransportStatus" to "OK", "CurrentSpeed" to "1")
            "GetPositionInfo" -> target.snapshot().let { s ->
                val has = s.state != "NO_MEDIA_PRESENT"
                listOf(
                    "Track" to if (has) "1" else "0",
                    "TrackDuration" to DlnaProtocol.formatTime(s.durationMs),
                    "TrackMetaData" to if (has) metadata else "",
                    "TrackURI" to s.url,
                    "RelTime" to DlnaProtocol.formatTime(s.positionMs),
                    "AbsTime" to DlnaProtocol.formatTime(s.positionMs),
                    "RelCount" to "2147483647",
                    "AbsCount" to "2147483647",
                )
            }
            "GetMediaInfo" -> target.snapshot().let { s ->
                listOf(
                    "NrTracks" to if (s.state == "NO_MEDIA_PRESENT") "0" else "1",
                    "MediaDuration" to DlnaProtocol.formatTime(s.durationMs),
                    "CurrentURI" to s.url, "CurrentURIMetaData" to metadata,
                    "NextURI" to "", "NextURIMetaData" to "",
                    "PlayMedium" to "NETWORK", "RecordMedium" to "NOT_IMPLEMENTED", "WriteStatus" to "NOT_IMPLEMENTED",
                )
            }
            "GetTransportSettings" -> listOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED")
            "GetCurrentTransportActions" -> listOf("Actions" to "Play,Pause,Stop,Seek")
            "GetDeviceCapabilities" -> listOf("PlayMedia" to "NETWORK", "RecMedia" to "NOT_IMPLEMENTED", "RecQualityModes" to "NOT_IMPLEMENTED")
            "GetVolume" -> listOf("CurrentVolume" to volume().toString())
            "SetVolume" -> emptyList<Pair<String, String>>().also { DlnaProtocol.arg(body, "DesiredVolume")?.toIntOrNull()?.let(::setVolume) }
            "GetMute" -> listOf("CurrentMute" to if (audio().isStreamMute(AudioManager.STREAM_MUSIC)) "1" else "0")
            "SetMute" -> emptyList<Pair<String, String>>().also {
                val mute = DlnaProtocol.arg(body, "DesiredMute").let { it == "1" || it.equals("true", true) }
                audio().adjustStreamVolume(AudioManager.STREAM_MUSIC, if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
            }
            "GetProtocolInfo" -> listOf("Source" to "", "Sink" to SINK)
            "GetCurrentConnectionIDs" -> listOf("ConnectionIDs" to "0")
            "GetCurrentConnectionInfo" -> listOf(
                "RcsID" to "0", "AVTransportID" to "0", "ProtocolInfo" to "", "PeerConnectionManager" to "",
                "PeerConnectionID" to "-1", "Direction" to "Input", "Status" to "OK",
            )
            else -> emptyList()
        }
        reply(out, 200, DlnaProtocol.response(service, action, values))
    }

    private fun audio() = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 音量按 0–100 报给手机。 */
    private fun volume(): Int {
        val a = audio()
        val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return a.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
    }

    private fun setVolume(percent: Int) {
        val a = audio()
        val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        a.setStreamVolume(AudioManager.STREAM_MUSIC, (percent.coerceIn(0, 100) * max + 50) / 100, 0)
    }

    private fun reply(out: OutputStream, code: Int, xml: String, extra: List<Pair<String, String>> = emptyList()) {
        val bytes = xml.toByteArray(Charsets.UTF_8)
        val status = when (code) { 200 -> "OK"; 404 -> "Not Found"; 412 -> "Precondition Failed"; else -> "Internal Server Error" }
        val head = buildString {
            append("HTTP/1.1 $code $status\r\n")
            if (bytes.isNotEmpty()) append("Content-Type: text/xml; charset=\"utf-8\"\r\n")
            append("Content-Length: ${bytes.size}\r\nServer: Android UPnP/1.0 TVPlayer/1.0\r\nEXT:\r\nConnection: close\r\n")
            extra.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }
        out.write(head.toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun readHead(input: InputStream): String? {
        val buf = java.io.ByteArrayOutputStream()
        var matched = 0
        while (buf.size() < 16_384) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            matched = if ((matched % 2 == 0 && b == '\r'.code) || (matched % 2 == 1 && b == '\n'.code)) matched + 1 else if (b == '\r'.code) 1 else 0
            if (matched == 4) return buf.toString(Charsets.UTF_8.name()).trimEnd()
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
        return if (read == n) out else out.copyOf(read)
    }
}
