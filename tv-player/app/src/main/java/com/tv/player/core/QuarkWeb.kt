package com.tv.player.core

import com.tv.player.data.Episode
import com.tv.player.data.PlayLine
import com.tv.player.data.VodDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

/**
 * 夸克网盘 through the web drive's API (the one pan.quark.cn uses). It is what saving ("转存") somebody's share into
 * your own drive needs; the TV-client token in [Quark] cannot do that. Login is the web QR login: the phone's Quark app
 * scans, and the web session cookie `__pus` (and later a refreshed `__puus`) comes back. Playing a file from here
 * needs those cookies on the download address too: [headersFor].
 */
object QuarkWeb {
    const val SOURCE_KEY = "quarkweb"
    const val APP_FOLDER = "TV Player"
    private const val SCHEME = "quarkweb://"
    private const val BASE = "https://drive-pc.quark.cn"
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch"
    private val json = "application/json".toMediaType()
    private val videoExt = setOf("mp4", "mkv", "avi", "mov", "m4v", "ts", "m2ts", "flv", "wmv", "rmvb", "webm", "mpg", "mpeg", "3gp")

    val loggedIn get() = Store.quarkWebPus().isNotEmpty()

    /** Set when the drive turned the session down; the player says so instead of a generic "no line works". */
    @Volatile var expired = false

    fun logout() {
        Store.setQuarkWebCookies("", "")
        expired = false
    }

    class ApiException(message: String, val loggedOut: Boolean = false) : Exception(message)

    class Share(val pwdId: String, val stoken: String, val title: String)

    class ShareFile(val fid: String, val name: String, val isDir: Boolean, val size: Long, val token: String) {
        val isVideo get() = !isDir && isVideoName(name)
    }

    class DriveFile(val fid: String, val name: String, val isDir: Boolean, val size: Long, val isVideo: Boolean)

    // ---- pure helpers (unit tested) -------------------------------------------------------------------------------

    /** "https://pan.quark.cn/s/<id>?pwd=<code>" or text with "提取码：<code>" -> share id and pass code ("" when none). */
    fun parseShare(text: String): Pair<String, String>? {
        val id = Regex("pan\\.quark\\.cn/s/([A-Za-z0-9]+)").find(text)?.groupValues?.get(1) ?: return null
        val code = Regex("[?&]pwd=([A-Za-z0-9]+)").find(text)?.groupValues?.get(1)
            ?: Regex("提取码[:：\\s]*([A-Za-z0-9]{4})").find(text)?.groupValues?.get(1).orEmpty()
        return id to code
    }

    /** A video a player can open: a disc image (.iso) is not one, whatever its size. */
    fun isVideoName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in videoExt

    fun folderName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|\\s]+"), " ").trim().take(60).ifEmpty { "分享" }

    fun isLink(url: String) = url.startsWith(SCHEME)

    private fun parseLink(url: String) = url.removePrefix(SCHEME)

    // ---- cookies ----------------------------------------------------------------------------------------------------

    private fun cookieHeader(): String = listOfNotNull(
        Store.quarkWebPus().takeIf { it.isNotEmpty() }?.let { "__pus=$it" },
        Store.quarkWebPuus().takeIf { it.isNotEmpty() }?.let { "__puus=$it" },
    ).joinToString("; ")

    /** The drive refreshes `__puus` in its answers; the next request (and the download) must carry the new one. */
    private fun remember(r: Response) {
        val cookies = Cookie.parseAll(r.request.url, r.headers)
        val puus = cookies.lastOrNull { it.name == "__puus" }?.value?.takeIf { it.isNotEmpty() }
        val pus = cookies.lastOrNull { it.name == "__pus" }?.value?.takeIf { it.isNotEmpty() }
        if (puus != null || pus != null) Store.setQuarkWebCookies(pus ?: Store.quarkWebPus(), puus ?: Store.quarkWebPuus())
    }

    /** Hosts of the download addresses this login was given: the only ones its cookies are sent to. */
    private val linkHosts: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Request headers a player must send to a drive address (cookies and the PC client's user agent); null for other hosts. */
    fun headersFor(host: String): Map<String, String>? =
        if (loggedIn && host in linkHosts) mapOf("Cookie" to cookieHeader(), "User-Agent" to UA) else null

    // ---- requests ---------------------------------------------------------------------------------------------------

    private suspend fun call(method: String, path: String, body: JSONObject? = null, params: Map<String, String> = emptyMap()): JSONObject =
        withContext(Dispatchers.IO) {
            val url = (BASE + path).toHttpUrl().newBuilder().addQueryParameter("pr", "ucpro").addQueryParameter("fr", "pc")
                .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
            val b = Request.Builder().url(url).header("User-Agent", UA).header("Cookie", cookieHeader())
            val req = if (body != null) b.post(body.toString().toRequestBody(json)).build() else b.get().build()
            Http.client.newCall(req).execute().use { r ->
                remember(r)
                val text = r.body?.string().orEmpty()
                if (r.code == 401) { expired = true; throw ApiException("网页版登录已失效，请重新扫码", loggedOut = true) }
                val o = runCatching { JSONObject(text) }.getOrElse { throw ApiException("夸克没有正常回应，稍后再试") }
                if (o.optInt("code") == 31001) { expired = true; throw ApiException("网页版登录已失效，请重新扫码", loggedOut = true) }
                if (o.optInt("code") != 0) throw ApiException(o.optString("message").ifEmpty { "夸克接口出错（${o.optInt("code")}）" })
                expired = false // the session answered: it is good
                o
            }
        }

    private suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        Http.client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { it.body?.string().orEmpty() }
    }

    // ---- web QR login -----------------------------------------------------------------------------------------------

    class Login(val qrUrl: String, val token: String, val requestId: String)

    suspend fun loginStart(): Login {
        val rid = UUID.randomUUID().toString()
        val o = runCatching { JSONObject(getText("https://uop.quark.cn/cas/ajax/getTokenForQrcodeLogin?client_id=532&v=1.2&request_id=$rid")) }
            .getOrElse { throw ApiException("连不上夸克，稍后再试") }
        val token = o.optJSONObject("data")?.optJSONObject("members")?.optString("token").orEmpty()
        if (token.isEmpty()) throw ApiException("没有拿到二维码，稍后再试")
        val qr = "https://su.quark.cn/4_eMHBJ?token=$token&client_id=532&ssb=weblogin&uc_param_str=&uc_biz_str=S%3Acustom%7COPT%3ASAREA%400%7COPT%3ABACK_BTN_STYLE%400"
        return Login(qr, token, rid)
    }

    /** One poll: true once the phone has approved and the session cookies are stored. */
    suspend fun loginPoll(l: Login): Boolean {
        val o = runCatching {
            JSONObject(getText("https://uop.quark.cn/cas/ajax/getServiceTicketByQrcodeToken?client_id=532&v=1.2&token=${l.token}&request_id=${l.requestId}"))
        }.getOrNull() ?: return false
        val ticket = o.optJSONObject("data")?.optJSONObject("members")?.optString("service_ticket").orEmpty()
        if (ticket.isEmpty()) return false
        // The ticket is traded for the session cookies; they arrive as Set-Cookie somewhere along the redirects.
        val seen = ArrayList<Cookie>()
        val client = Http.client.newBuilder().cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) { seen += cookies }
            override fun loadForRequest(url: HttpUrl): List<Cookie> = emptyList()
        }).build()
        // A network error here is not a verdict on the ticket: the next poll tries again.
        val reached = withContext(Dispatchers.IO) {
            runCatching { client.newCall(Request.Builder().url("https://pan.quark.cn/account/info?st=${URLEncoder.encode(ticket, "UTF-8")}&lw=scan").header("User-Agent", UA).build()).execute().close() }.isSuccess
        }
        if (!reached) return false
        val pus = seen.lastOrNull { it.name == "__pus" }?.value ?: throw ApiException("登录没有成功，请重新扫码")
        Store.setQuarkWebCookies(pus, seen.lastOrNull { it.name == "__puus" }?.value.orEmpty())
        expired = false
        return true
    }

    // ---- shares (browsing one needs no login) ------------------------------------------------------------------------

    suspend fun openShare(pwdId: String, passcode: String): Share {
        val o = call("POST", "/1/clouddrive/share/sharepage/token", JSONObject().put("pwd_id", pwdId).put("passcode", passcode))
        val d = o.optJSONObject("data") ?: throw ApiException("分享打不开")
        return Share(pwdId, d.optString("stoken"), d.optString("title").ifEmpty { "分享" })
    }

    suspend fun shareList(share: Share, pdirFid: String): List<ShareFile> {
        val out = ArrayList<ShareFile>()
        var page = 1
        while (true) {
            val o = call(
                "GET", "/1/clouddrive/share/sharepage/detail",
                params = mapOf("pwd_id" to share.pwdId, "stoken" to share.stoken, "pdir_fid" to pdirFid, "_page" to page.toString(), "_size" to "50", "_fetch_total" to "1"),
            )
            val list = o.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
            for (i in 0 until list.length()) {
                val f = list.optJSONObject(i) ?: continue
                out += ShareFile(f.optString("fid"), f.optString("file_name"), f.optBoolean("dir"), f.optLong("size"), f.optString("share_fid_token"))
            }
            val total = o.optJSONObject("metadata")?.optInt("_total") ?: list.length()
            if (list.length() == 0 || out.size >= total) break
            page++
        }
        return out.sortedWith(compareByDescending<ShareFile> { it.isDir }.thenBy { it.name })
    }

    // ---- your drive -------------------------------------------------------------------------------------------------

    suspend fun list(parentFid: String): List<DriveFile> {
        val out = ArrayList<DriveFile>()
        var page = 1
        while (true) {
            val o = call("GET", "/1/clouddrive/file/sort", params = mapOf("pdir_fid" to parentFid, "_page" to page.toString(), "_size" to "100", "_fetch_total" to "1"))
            val list = o.optJSONObject("data")?.optJSONArray("list") ?: JSONArray()
            for (i in 0 until list.length()) {
                val f = list.optJSONObject(i) ?: continue
                val name = f.optString("file_name")
                out += DriveFile(f.optString("fid"), name, f.optBoolean("dir"), f.optLong("size"), !f.optBoolean("dir") && isVideoName(name))
            }
            val total = o.optJSONObject("metadata")?.optInt("_total") ?: list.length()
            if (list.length() == 0 || out.size >= total) break
            page++
        }
        return out.sortedWith(compareByDescending<DriveFile> { it.isDir }.thenBy { it.name })
    }

    /** The folder named [name] inside [parentFid], created when it is not there yet. */
    suspend fun ensureFolder(parentFid: String, name: String): String {
        list(parentFid).firstOrNull { it.isDir && it.name == name }?.let { return it.fid }
        val o = call("POST", "/1/clouddrive/file", JSONObject().put("pdir_fid", parentFid).put("file_name", name).put("dir_path", "").put("dir_init_lock", false))
        return o.optJSONObject("data")?.optString("fid")?.takeIf { it.isNotEmpty() } ?: throw ApiException("建不了文件夹「$name」")
    }

    /** Saves [file] (found in the share's folder [pdirFid]) into your drive's folder [toFid]; returns the new file's id when known. */
    suspend fun save(share: Share, file: ShareFile, pdirFid: String, toFid: String): String? {
        val body = JSONObject()
            .put("fid_list", JSONArray().put(file.fid)).put("fid_token_list", JSONArray().put(file.token))
            .put("to_pdir_fid", toFid).put("pwd_id", share.pwdId).put("stoken", share.stoken).put("pdir_fid", pdirFid).put("scene", "link")
        val o = call("POST", "/1/clouddrive/share/sharepage/save", body)
        val task = o.optJSONObject("data")?.optString("task_id").orEmpty()
        if (task.isEmpty()) throw ApiException("转存没有开始")
        repeat(40) { i ->
            delay(700)
            val t = call("GET", "/1/clouddrive/task", params = mapOf("task_id" to task, "retry_index" to i.toString())).optJSONObject("data") ?: return@repeat
            when (val status = t.optInt("status")) {
                2 -> return t.optJSONObject("save_as")?.optJSONArray("save_as_top_fids")?.optString(0)?.takeIf { it.isNotEmpty() }
                in 3..99 -> throw ApiException(t.optString("task_title").ifEmpty { "转存失败（$status）" })
            }
        }
        throw ApiException("转存超时，稍后在网盘里看看有没有")
    }

    // ---- playing ----------------------------------------------------------------------------------------------------

    /** A folder of your drive as a series for the player: its videos are the episodes, one line (the file itself). */
    suspend fun detail(folderFid: String, title: String): VodDetail? {
        val videos = list(folderFid).filter { it.isVideo }
        if (videos.isEmpty()) return null
        val line = PlayLine("夸克网盘 · 原画", videos.map { Episode(it.name.substringBeforeLast('.'), "$SCHEME${it.fid}") })
        return VodDetail(title, "", listOf(line), remarks = "共 ${videos.size} 个视频")
    }

    /** The address of a drive file; the player must send [headersFor] with it. */
    suspend fun link(url: String): String? {
        val o = call("POST", "/1/clouddrive/file/download", JSONObject().put("fids", JSONArray().put(parseLink(url))))
        val url = o.optJSONArray("data")?.optJSONObject(0)?.optString("download_url")?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { java.net.URI(url).host }.getOrNull()?.let { linkHosts += it }
        return url
    }

    /** Episode links for [fid] (used to start on a particular file). */
    fun episodeLink(fid: String) = "$SCHEME$fid"
}
