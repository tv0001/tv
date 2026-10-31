package com.tv.player.core

import android.util.Base64
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import com.tv.player.data.Episode
import com.tv.player.data.PlayLine
import com.tv.player.data.VodDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * 网络存储：家里 NAS、路由器、OpenList / Alist 这类挂载服务上的视频。两种协议：WebDAV（OpenList / Alist 的 /dav、群晖、
 * 威联通都有）和 SMB（Windows 共享、多数 NAS）。和夸克网盘一样，一个文件夹当成一部片，里面的视频是选集。
 * 账号密码只存在本机（不进备份，不打日志）。
 */
object NetStore {
    const val SOURCE_KEY = "netstore"
    const val SMB_SCHEME = "tvpsmb" // 播放地址 tvpsmb://<存储编号>/<共享里的路径>，账号密码不进地址

    data class Server(val id: String, val kind: String, val name: String, val address: String, val user: String, val pass: String) {
        val isSmb get() = kind == "smb"
    }

    data class Entry(val name: String, val path: String, val dir: Boolean, val size: Long) {
        val isVideo get() = !dir && isVideoName(name)
    }

    private val videoExt = setOf("mp4", "m4v", "mkv", "webm", "ts", "m2ts", "mts", "flv", "avi", "mov", "mpg", "mpeg", "3gp")

    fun isVideoName(name: String) = name.substringAfterLast('.', "").lowercase() in videoExt

    // ---- 保存的存储 ----------------------------------------------------------------------------------------------

    fun servers(): List<Server> = runCatching {
        val a = JSONArray(Store.netServersJson())
        (0 until a.length()).map { i ->
            a.getJSONObject(i).let { Server(it.getString("id"), it.getString("kind"), it.getString("name"), it.getString("address"), it.optString("user"), it.optString("pass")) }
        }
    }.getOrDefault(emptyList())

    fun server(id: String) = servers().firstOrNull { it.id == id }

    fun save(list: List<Server>) = Store.setNetServersJson(JSONArray().also { a ->
        list.forEach { a.put(JSONObject().put("id", it.id).put("kind", it.kind).put("name", it.name).put("address", it.address).put("user", it.user).put("pass", it.pass)) }
    }.toString())

    /** 先连一次、列一次根目录，通过了才保存。返回 null 表示成功，否则是给用户看的原因。 */
    suspend fun add(kind: String, name: String, address: String, user: String, pass: String): String? {
        val clean = address.trim()
        if (kind == "webdav" && !clean.startsWith("http://") && !clean.startsWith("https://")) return "WebDAV 地址要以 http:// 或 https:// 开头"
        if (kind == "smb" && parseSmb(clean) == null) return "SMB 地址的写法是 smb://主机/共享名，比如 smb://192.168.1.10/movies"
        val server = Server(java.util.UUID.randomUUID().toString().take(8), kind, name.trim().ifEmpty { defaultName(kind, clean) }, clean, user.trim(), pass)
        return try {
            list(server, "/")
            save(servers() + server)
            null
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { dropConnection(server.id) }
            "连不上：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 断开连接可能要等网络，放在 IO 线程。 */
    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        dropConnection(id)
        save(servers().filter { it.id != id })
    }

    private fun defaultName(kind: String, address: String) = if (kind == "smb") parseSmb(address)?.let { "${it.host}/${it.share}" } ?: address
    else runCatching { URI(address).host }.getOrNull() ?: address

    // ---- 列目录 --------------------------------------------------------------------------------------------------

    /** [path] 以 / 开头，是相对存储根的路径。文件夹在前，再按名字的自然顺序（第 2 集在第 10 集前面）。 */
    suspend fun list(server: Server, path: String): List<Entry> = withContext(Dispatchers.IO) {
        val entries = if (server.isSmb) smbList(server, path) else davList(server, path)
        entries.sortedWith(compareBy<Entry> { !it.dir }.then { a, b -> naturalCompare(a.name, b.name) })
    }

    /** 播放页取片用：id 是「存储编号|文件夹路径」，文件夹里的视频是选集。 */
    suspend fun detail(id: String, title: String): VodDetail? {
        val server = server(id.substringBefore('|')) ?: return null
        val folder = id.substringAfter('|', "/")
        val videos = list(server, folder).filter { it.isVideo }
        if (videos.isEmpty()) return null
        return VodDetail(title, "", listOf(PlayLine("原画", videos.map { Episode(it.name.substringBeforeLast('.'), playUrl(server, it.path)) })))
    }

    fun playUrl(server: Server, path: String): String =
        if (server.isSmb) "$SMB_SCHEME://${server.id}${encodePath(path)}" else davUrl(server, path)

    /** 播放时 WebDAV 文件的请求要带上账号；其它地址（包括同一网段的别的主机）返回 null。 */
    fun headersFor(url: String): Map<String, String>? =
        servers().firstOrNull { !it.isSmb && belongs(url, it) }?.let(::davAuth)

    /** [url] 是不是这个 WebDAV 存储里的：协议、主机、端口都一样，路径在存储根下面。只比前缀会把 192.168.1.1 和 192.168.1.10 当成一台。 */
    fun belongs(url: String, s: Server): Boolean {
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        val b = runCatching { URI(davBase(s)) }.getOrNull() ?: return false
        fun port(x: URI) = if (x.port >= 0) x.port else if (x.scheme.equals("https", true)) 443 else 80
        if (!u.scheme.equals(b.scheme, true) || !u.host.equals(b.host, true) || port(u) != port(b)) return false
        val base = b.rawPath.orEmpty().trimEnd('/')
        val path = u.rawPath.orEmpty()
        return base.isEmpty() || path == base || path.startsWith("$base/")
    }

    /**
     * 播放前先看一眼 WebDAV 文件会不会被重定向（OpenList / Alist 常把文件 302 到网盘的下载地址）：跳到别的主机就直接播那个地址，
     * 这样账号密码只发给存储本身。播放器自己跟随重定向时会把认证头原样带过去，所以不能交给它。
     */
    suspend fun resolve(url: String): String = withContext(Dispatchers.IO) {
        val s = servers().firstOrNull { !it.isSmb && belongs(url, it) } ?: return@withContext url
        val client = Http.client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        var current = url
        repeat(5) {
            val request = Request.Builder().url(current).header("Range", "bytes=0-0")
                .apply { if (belongs(current, s)) davAuth(s)?.forEach { (k, v) -> header(k, v) } }
                .build()
            val next = runCatching {
                client.newCall(request).execute().use { r -> if (r.isRedirect) r.header("Location")?.let { loc -> URI(current).resolve(loc).toString() } else null }
            }.getOrNull() ?: return@withContext current
            current = next
        }
        current
    }

    private fun davAuth(s: Server): Map<String, String>? {
        if (s.user.isEmpty()) return null
        val token = Base64.encodeToString("${s.user}:${s.pass}".toByteArray(), Base64.NO_WRAP)
        return mapOf("Authorization" to "Basic $token")
    }

    fun isLink(url: String) = url.startsWith("$SMB_SCHEME://") || servers().any { !it.isSmb && belongs(url, it) }

    // ---- WebDAV --------------------------------------------------------------------------------------------------

    private fun davBase(s: Server) = s.address.trimEnd('/')

    private fun davUrl(s: Server, path: String) = davBase(s) + encodePath(path)

    private fun davList(server: Server, path: String): List<Entry> {
        val dir = if (path.endsWith("/")) path else "$path/"
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:propfind xmlns:D=\"DAV:\"><D:prop><D:displayname/><D:getcontentlength/><D:resourcetype/></D:prop></D:propfind>"
        val request = Request.Builder().url(davUrl(server, dir))
            .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1")
            .apply { davAuth(server)?.forEach { (k, v) -> header(k, v) } } // 添加时还没保存，所以按这个存储本身算
            .build()
        Http.client.newCall(request).execute().use { r ->
            if (r.code == 401) throw IllegalStateException("用户名或密码不对")
            if (r.code != 207 && r.code != 200) throw IllegalStateException("HTTP ${r.code}")
            return parseMultistatus(r.body?.string().orEmpty(), URI(davBase(server)).rawPath.orEmpty(), dir)
        }
    }

    /**
     * PROPFIND 的应答（multistatus）：每个 response 的 href 去掉存储根的路径，就是相对路径；第一个通常是文件夹自己，跳过。
     * 命名空间前缀各家不同（D:、d:、lp1:、没有），按元素本名匹配。
     */
    fun parseMultistatus(xml: String, basePath: String, dir: String): List<Entry> {
        val base = decode(basePath).trimEnd('/')
        val self = dir.trimEnd('/')
        return Regex("""<(?:[\w-]+:)?response\b[^>]*>(.*?)</(?:[\w-]+:)?response>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val block = m.groupValues[1]
            val href = tag(block, "href") ?: return@mapNotNull null
            val full = decode(runCatching { URI(href.trim()).rawPath }.getOrNull() ?: href.trim()).trimEnd('/')
            if (!full.startsWith(base)) return@mapNotNull null
            val rel = full.removePrefix(base).ifEmpty { "/" }
            if (rel.trimEnd('/') == self) return@mapNotNull null
            val isDir = Regex("""<(?:[\w-]+:)?collection\b""").containsMatchIn(block)
            val name = tag(block, "displayname")?.takeIf { it.isNotBlank() } ?: rel.substringAfterLast('/')
            Entry(name, rel, isDir, tag(block, "getcontentlength")?.trim()?.toLongOrNull() ?: 0)
        }.toList()
    }

    private fun tag(block: String, name: String): String? =
        Regex("""<(?:[\w-]+:)?$name\b[^>]*>(.*?)</(?:[\w-]+:)?$name>""", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)
            ?.replace("&amp;", "&")?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&quot;", "\"")?.replace("&#39;", "'")

    private fun decode(s: String) = runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)

    /** 路径逐段编码，斜杠保留。 */
    fun encodePath(path: String): String = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    // ---- SMB -----------------------------------------------------------------------------------------------------

    data class SmbAddress(val host: String, val port: Int, val share: String, val base: String)

    /** smb://主机[:端口]/共享名[/子目录]，也认 \\主机\共享名 和不带 smb:// 的写法。 */
    fun parseSmb(address: String): SmbAddress? {
        val t = address.trim().replace('\\', '/').removePrefix("smb://").removePrefix("SMB://").trimStart('/')
        val parts = t.split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val host = parts[0].substringBefore(':')
        val port = parts[0].substringAfter(':', "445").toIntOrNull() ?: return null
        if (host.isEmpty()) return null
        return SmbAddress(host, port, parts[1], parts.drop(2).joinToString("/"))
    }

    private val smbClient by lazy {
        SMBClient(
            SmbConfig.builder()
                .withTimeout(15, TimeUnit.SECONDS)
                .withSoTimeout(30, TimeUnit.SECONDS)
                .withSecurityProvider(BCSecurityProvider()) // 安卓自带的加密库没有 NTLM 要用的 MD4
                .build(),
        )
    }

    private class SmbHandle(val connection: Connection, val share: DiskShare)

    private val smbHandles = HashMap<String, SmbHandle>()

    /** 锁只护着连接表；建连放在锁外面：NAS 不通时 smbj 可能卡一两分钟，不能让删除存储之类的操作跟着等。 */
    private fun share(server: Server): DiskShare {
        val stale = synchronized(this) {
            smbHandles[server.id]?.let { if (it.connection.isConnected && it.share.isConnected) return it.share }
            smbHandles.remove(server.id)
        }
        stale?.let { runCatching { it.connection.close() } }
        val a = parseSmb(server.address) ?: throw IllegalStateException("SMB 地址不对")
        val connection = smbClient.connect(a.host, a.port)
        val auth = if (server.user.isEmpty()) AuthenticationContext.guest()
        else AuthenticationContext(server.user.substringAfter('\\'), server.pass.toCharArray(), server.user.substringBefore('\\', "").ifEmpty { null })
        val share = try {
            connection.authenticate(auth).connectShare(a.share) as? DiskShare ?: throw IllegalStateException("「${a.share}」不是文件共享")
        } catch (e: Exception) {
            runCatching { connection.close() }
            throw e
        }
        // 建连期间别的线程可能已经连好了：用先放进去的那个，这个关掉
        val other = synchronized(this) {
            smbHandles[server.id]?.takeIf { it.connection.isConnected && it.share.isConnected }
                ?: run { smbHandles[server.id] = SmbHandle(connection, share); null }
        }
        if (other != null) {
            runCatching { connection.close() }
            return other.share
        }
        return share
    }

    private fun dropConnection(id: String) {
        val handle = synchronized(this) { smbHandles.remove(id) }
        handle?.let { runCatching { it.connection.close() } }
    }

    /** 共享里的路径（反斜杠分隔，不以斜杠开头）。 */
    private fun smbPath(server: Server, path: String): String {
        val base = parseSmb(server.address)?.base.orEmpty()
        return (base + "/" + path.trim('/')).trim('/').replace('/', '\\')
    }

    /** 连接断了（NAS 休眠、换网）才重连一次；只是这个文件打不开、没权限之类的错，不动共用的连接（别的文件可能正在读）。 */
    private fun <T> withShare(server: Server, block: (DiskShare) -> T): T = try {
        block(share(server))
    } catch (e: Exception) {
        val alive = synchronized(this) { smbHandles[server.id]?.let { it.connection.isConnected && it.share.isConnected } == true }
        if (alive) throw e
        dropConnection(server.id)
        block(share(server))
    }

    private fun smbList(server: Server, path: String): List<Entry> = withShare(server) { share ->
        share.list(smbPath(server, path)).filter { it.fileName != "." && it.fileName != ".." && !it.fileName.startsWith(".") }.map {
            val dir = it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
            Entry(it.fileName, path.trimEnd('/') + "/" + it.fileName, dir, it.endOfFile)
        }
    }

    /** 播放用：打开共享里的一个文件（只读）。地址是 [playUrl] 给出的 tvpsmb://。 */
    fun openSmb(url: String): Pair<SmbFile, Long> {
        val uri = URI(url)
        val server = server(uri.host ?: "") ?: throw IllegalStateException("这个网络存储已经删掉了")
        val path = smbPath(server, URLDecoder.decode(uri.rawPath.replace("+", "%2B"), "UTF-8"))
        return withShare(server) { share ->
            val f = share.openFile(path, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
            f to f.fileInformation.standardInformation.endOfFile
        }
    }

    // ---- 自然排序 ------------------------------------------------------------------------------------------------

    fun naturalCompare(a: String, b: String): Int {
        val x = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
        val y = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) (p.toBigInteger().compareTo(q.toBigInteger())) else p.compareTo(q)
            if (c != 0) return c
        }
        return x.size - y.size
    }
}
