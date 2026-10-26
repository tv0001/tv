package com.tv.player.core

import android.util.Base64
import com.tv.player.data.Episode
import com.tv.player.data.PlayLine
import com.tv.player.data.VodDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 夸克网盘 through the API its own TV client uses (the one OpenList's "QuarkTV" driver talks to): the TV shows a QR
 * code, the phone's Quark app scans and approves it, and the TV gets a refresh token. Requests are signed with the TV
 * client's key; the code-for-token exchange goes through api.extscreen.com, which holds that client's secret.
 */
object Quark {
    const val SOURCE_KEY = "quark"
    const val SOURCE_NAME = "夸克网盘"
    const val ROOT = "0"
    private const val SCHEME = "quark://"
    private const val API = "https://open-api-drive.quark.cn"
    private const val CODE_API = "http://api.extscreen.com/quarkdrive"
    private const val CLIENT_ID = "d3194e61504e493eb6222857bccfed94"
    private const val SIGN_KEY = "kw2dvtd7p4t3pjl2d9ed9yc8yej8kw2d"
    private const val UA = "Mozilla/5.0 (Linux; U; Android 13; zh-cn; M2004J7AC Build/UKQ1.231108.001) AppleWebKit/533.1 (KHTML, like Gecko) Mobile Safari/533.1"
    private val device = mapOf(
        "app_ver" to "1.8.2.2", "channel" to "GENERAL", "platform" to "tv", "device_brand" to "Xiaomi",
        "device_name" to "M2004J7AC", "device_model" to "M2004J7AC", "build_device" to "M2004J7AC", "build_product" to "M2004J7AC",
        "device_gpu" to "Adreno (TM) 550", "activity_rect" to "{}",
    )

    /** The qualities offered as lines: the file itself, then the drive's transcodes from best to smallest. */
    val qualities = listOf("download" to "原画", "super" to "超清", "high" to "高清", "normal" to "普通")

    class File(val fid: String, val name: String, val isDir: Boolean, val size: Long, val category: Int, val updatedAt: Long, val thumb: String) {
        val isVideo get() = !isDir && category == 1
    }

    /** The QR code (a JPEG) to show and the token to poll with. */
    class Login(val qrJpeg: ByteArray, val queryToken: String)

    class ApiException(message: String, val loggedOut: Boolean = false) : Exception(message)

    val loggedIn get() = Store.quarkRefreshToken().isNotEmpty()

    fun logout() = Store.setQuarkTokens("", "")

    // ---- signing ---------------------------------------------------------------------------------------------------

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun md5(s: String) = hex(MessageDigest.getInstance("MD5").digest(s.toByteArray()))
    private fun sha256(s: String) = hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()))

    private fun deviceId(): String = Store.quarkDeviceId().ifEmpty { md5(System.currentTimeMillis().toString()).also(Store::setQuarkDeviceId) }

    private class Sign(val tm: String, val token: String, val reqId: String)

    /** The TV client's request signature: a timestamp, a hash over method, path, time and key, and a request id. */
    private fun sign(method: String, path: String): Sign {
        val tm = System.currentTimeMillis().toString()
        return Sign(tm, signToken(method, path, tm), md5(deviceId() + tm))
    }

    fun signToken(method: String, path: String, tm: String): String = sha256("$method&$path&$tm&$SIGN_KEY")

    /** The drive's way of saying the access token is no longer good. */
    fun tokenInvalid(status: Int, errno: Int, info: String): Boolean {
        val s = info.lowercase()
        return (status == -1 && (errno == 10001 || errno == 11001)) ||
            (s.isNotEmpty() && ("access token" in s || "access_token" in s || "token无效" in s || "token 无效" in s))
    }

    // ---- requests --------------------------------------------------------------------------------------------------

    /**
     * GET on the drive API with the signature and device fields; an expired access token is refreshed once. With
     * [lenient] an error answer is returned instead of thrown (the login poll is "wrong" until the phone approves).
     */
    private suspend fun request(path: String, params: Map<String, String>, retry: Boolean = true, lenient: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        val s = sign("GET", path)
        val url = (API + path).toHttpUrl().newBuilder().apply {
            addQueryParameter("req_id", s.reqId)
            addQueryParameter("access_token", Store.quarkAccessToken())
            addQueryParameter("device_id", deviceId())
            device.forEach { (k, v) -> addQueryParameter(k, v) }
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val req = Request.Builder().url(url)
            .header("Accept", "application/json, text/plain, */*").header("User-Agent", UA)
            .header("x-pan-tm", s.tm).header("x-pan-token", s.token).header("x-pan-client-id", CLIENT_ID)
            .build()
        val text = Http.client.newCall(req).execute().use { it.body?.string().orEmpty() }
        val o = runCatching { JSONObject(text) }.getOrElse { throw ApiException("夸克没有正常回应，稍后再试") }
        val status = o.optInt("status", 0)
        val errno = o.optInt("errno", 0)
        val info = o.optString("error_info")
        if (tokenInvalid(status, errno, info)) {
            if (retry && Store.quarkRefreshToken().isNotEmpty()) {
                try {
                    refreshTokens()
                } catch (e: ApiException) {
                    throw ApiException("登录已失效，请重新扫码", loggedOut = true) // the drive refused the refresh token
                } catch (e: Exception) {
                    throw ApiException("连不上夸克：${e.message ?: "网络不通"}") // a network blip is not a logout
                }
                return@withContext request(path, params, retry = false, lenient = lenient)
            }
            throw ApiException("登录已失效，请重新扫码", loggedOut = true)
        }
        if (!lenient && (status >= 400 || errno != 0)) throw ApiException(info.ifEmpty { "夸克接口出错（$status/$errno）" })
        o
    }

    /** `code` (after a scan) or `refresh_token` for a new pair of tokens, through the server that holds the secret. */
    private suspend fun exchange(grant: Pair<String, String>) = withContext(Dispatchers.IO) {
        val s = sign("POST", "/token")
        val body = JSONObject().apply {
            put("req_id", s.reqId)
            put("device_id", deviceId())
            device.forEach { (k, v) -> put(k, v) }
            put(grant.first, grant.second)
        }
        val req = Request.Builder().url("$CODE_API/token")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = Http.client.newCall(req).execute().use { it.body?.string().orEmpty() }
        // Not JSON (an error page, a timeout): not an answer from the drive, so not a rejection.
        val o = runCatching { JSONObject(text) }.getOrElse { throw java.io.IOException("换取登录凭证失败，稍后再试") }
        if (o.optInt("code") != 200) throw ApiException(o.optString("message").ifEmpty { "换取登录凭证失败" })
        val d = o.optJSONObject("data") ?: throw java.io.IOException("换取登录凭证失败")
        val refresh = d.optString("refresh_token")
        if (refresh.isEmpty()) throw ApiException("没有拿到登录凭证")
        Store.setQuarkTokens(refresh, d.optString("access_token"))
    }

    private suspend fun refreshTokens() = exchange("refresh_token" to Store.quarkRefreshToken())

    // ---- login -----------------------------------------------------------------------------------------------------

    suspend fun loginStart(): Login {
        val o = request(
            "/oauth/authorize",
            mapOf("auth_type" to "code", "client_id" to CLIENT_ID, "scope" to "netdisk", "qrcode" to "1", "qr_width" to "460", "qr_height" to "460"),
        )
        val qr = o.optString("qr_data")
        val token = o.optString("query_token")
        if (qr.isEmpty() || token.isEmpty()) throw ApiException("没有拿到二维码，稍后再试")
        return Login(Base64.decode(qr, Base64.DEFAULT), token)
    }

    /** One poll: true once the phone has approved and the tokens are stored. Before that the drive answers "用户未确认授权". */
    suspend fun loginPoll(queryToken: String): Boolean {
        val o = request("/oauth/code", mapOf("client_id" to CLIENT_ID, "scope" to "netdisk", "query_token" to queryToken), lenient = true)
        val code = o.optString("code")
        if (code.isEmpty()) return false
        exchange("code" to code)
        return true
    }

    // ---- files -----------------------------------------------------------------------------------------------------

    /** Everything in the folder [parentFid] ([ROOT] for the top), folders first, then by name. */
    suspend fun list(parentFid: String): List<File> {
        val out = ArrayList<File>()
        var page = 0
        while (true) {
            val o = request(
                "/file",
                mapOf(
                    "method" to "list", "parent_fid" to parentFid, "order_by" to "1", "desc" to "0", "category" to "", "source" to "",
                    "ex_source" to "", "list_all" to "0", "page_size" to "100", "page_index" to page.toString(),
                ),
            )
            val data = o.optJSONObject("data") ?: break
            val files = data.optJSONArray("files") ?: break
            for (i in 0 until files.length()) {
                val f = files.optJSONObject(i) ?: continue
                out += File(
                    f.optString("fid"), f.optString("filename"), f.optInt("isdir") == 1, f.optLong("size"), f.optInt("category"),
                    f.optLong("updated_at"), f.optString("thumbnail_url"),
                )
            }
            if (files.length() == 0 || (page + 1) * 100L >= data.optLong("total_count")) break
            page++
        }
        return out.sortedWith(compareByDescending<File> { it.isDir }.thenBy { it.name })
    }

    /** A folder as a series for the player: its video files are the episodes, one line per quality. */
    suspend fun detail(folderFid: String, title: String): VodDetail? {
        val videos = list(folderFid).filter { it.isVideo }
        if (videos.isEmpty()) return null
        val lines = qualities.map { (q, label) ->
            PlayLine("$SOURCE_NAME · $label", videos.map { Episode(it.name.substringBeforeLast('.'), "$SCHEME${it.fid}?q=$q") })
        }
        return VodDetail(title, videos.firstOrNull { it.thumb.isNotEmpty() }?.thumb.orEmpty(), lines, remarks = "共 ${videos.size} 个视频")
    }

    fun isLink(url: String) = url.startsWith(SCHEME) || QuarkWeb.isLink(url)

    /** "quark://<fid>?q=<quality>" -> fid and quality. */
    fun parseLink(url: String): Pair<String, String> {
        val rest = url.removePrefix(SCHEME)
        return rest.substringBefore('?') to rest.substringAfter("q=", "download").substringBefore('&')
    }

    /**
     * A playable address for an episode link, fetched when it is about to play (the drive's addresses expire). The
     * file itself for "download"; otherwise the transcode at that quality, or the nearest one the drive has ready.
     */
    suspend fun link(url: String): String? {
        val (fid, quality) = parseLink(url)
        val common = mapOf("group_by" to "source", "fid" to fid, "resolution" to "low,normal,high,super,2k,4k", "support" to "dolby_vision")
        if (quality != "download") {
            val infos = request("/file", common + ("method" to "streaming")).optJSONObject("data")?.optJSONArray("video_info")
            val ready = (0 until (infos?.length() ?: 0)).mapNotNull { infos!!.optJSONObject(it) }
                .filter { it.optString("url").isNotEmpty() }
            val order = listOf("4k", "2k", "super", "high", "normal", "low")
            val want = order.indexOf(quality).takeIf { it >= 0 } ?: 2
            // That quality, else the next smaller one that is ready, else the best there is.
            val pick = ready.filter { order.indexOf(it.optString("resolution")) >= want }.minByOrNull { order.indexOf(it.optString("resolution")) }
                ?: ready.minByOrNull { order.indexOf(it.optString("resolution")).let { i -> if (i < 0) order.size else i } }
            pick?.optString("url")?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return request("/file", common + ("method" to "download")).optJSONObject("data")?.optString("download_url")?.takeIf { it.isNotEmpty() }
    }
}
