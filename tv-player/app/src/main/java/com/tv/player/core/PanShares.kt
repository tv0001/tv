package com.tv.player.core

import android.util.Log
import com.tv.player.data.Episode
import com.tv.player.data.PlayLine
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * jar 网盘资源站（PanWebShare、Wogg、4K 指南这一类）的夸克分享。这些爬虫要用网盘登录把分享换成文件列表，没登录时详情
 * 是空的；本应用不把登录交给 jar（见 SpiderHost），而是自己打开站点的详情页找出夸克分享链接，不登录也能列出里面的视频；
 * 真正播放时才转存到自己网盘的「TV Player / 分享标题」，用本应用的夸克登录放（和手动添加分享链接一样，见 ShareActivity）。
 * 只认夸克的分享；UC、阿里等别家网盘的不支持。
 */
object PanShares {
    private const val TAG = "PanShares"
    private const val SCHEME = "quarkshare://"
    private const val MAX_SHARES = 3
    // 只吃网址里的字符：链接后面紧跟「提取码：1234」这类中文时不能连它一起吞进去
    private val shareLink = Regex("https?://pan\\.quark\\.cn/s/[A-Za-z0-9]+(?:[?#][A-Za-z0-9=&_%.-]*)?")

    fun isLink(url: String) = url.startsWith(SCHEME)

    /**
     * 网页里的夸克分享（分享号, 提取码），去重。提取码在链接参数里（?pwd=），或写在链接后面（「提取码：xxxx」）——
     * 只在到下一个链接之前的那段里找，不然会拿到下一个分享的码。
     */
    fun findShares(html: String): List<Pair<String, String>> {
        val links = shareLink.findAll(html).toList()
        return links.mapIndexedNotNull { i, m ->
            val id = Regex("/s/([A-Za-z0-9]+)").find(m.value)?.groupValues?.get(1) ?: return@mapIndexedNotNull null
            val code = Regex("[?&]pwd=([A-Za-z0-9]+)").find(m.value)?.groupValues?.get(1) ?: run {
                val end = minOf(html.length, m.range.last + 61, links.getOrNull(i + 1)?.range?.first ?: html.length)
                Regex("提取码[:：\\s]*([A-Za-z0-9]{4})").find(html.substring(m.range.last + 1, end))?.groupValues?.get(1).orEmpty()
            }
            id to code
        }.groupBy { it.first }.map { (id, found) -> id to (found.firstOrNull { it.second.isNotEmpty() }?.second ?: "") } // 同一分享出现几次：取带码的那次
    }

    /** 每个分享一条线路，选集是里面的视频（往下看两层文件夹）；打不开的分享跳过。 */
    suspend fun lines(shares: List<Pair<String, String>>): List<PlayLine> {
        val out = ArrayList<PlayLine>()
        for ((id, code) in shares.take(MAX_SHARES)) {
            runCatching {
                val share = QuarkWeb.openShare(id, code)
                val videos = videosIn(share, "0", 2)
                if (videos.isNotEmpty()) out += PlayLine(
                    "夸克 · ${share.title.take(16)}",
                    videos.map { (dir, f) -> Episode(episodeName(f.name), link(share, code, dir, f)) },
                )
            }.onFailure { Log.w(TAG, "分享 $id 打不开：${it.message}") }
        }
        return out
    }

    /** 分享里的文件名常是「[剧名].英文名.2024.S02E01.2160p.WEB-DL…」：有季集号时显示成「第1集」。 */
    fun episodeName(file: String): String {
        Regex("[Ss]\\d{1,2}[Ee](\\d{1,3})").find(file)?.let { return "第${it.groupValues[1].toInt()}集" }
        return file.substringBeforeLast('.')
    }

    private suspend fun videosIn(share: QuarkWeb.Share, dir: String, depth: Int): List<Pair<String, QuarkWeb.ShareFile>> {
        val files = QuarkWeb.shareList(share, dir)
        val here = files.filter { !it.isDir && QuarkWeb.isVideoName(it.name) }.map { dir to it }
        if (here.isNotEmpty() || depth <= 0) return here
        return files.filter { it.isDir }.take(5).flatMap { videosIn(share, it.fid, depth - 1) }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")

    /** quarkshare://分享号/提取码/所在目录/文件号/文件令牌/大小/文件名/分享标题 */
    private fun link(share: QuarkWeb.Share, code: String, dir: String, f: QuarkWeb.ShareFile) =
        SCHEME + listOf(share.pwdId, code, dir, f.fid, f.token, f.size.toString(), f.name, share.title).joinToString("/") { enc(it) }

    /**
     * 播放时：转存到「TV Player / 分享标题」（转存过的直接用），有电视端登录时放它的转码线路（原文件的地址常常慢），
     * 不然用网页版登录放原文件。没有网页版登录时转存不了，返回 null（这条线路算失败）。
     */
    suspend fun stream(url: String): String? {
        val p = url.removePrefix(SCHEME).split('/').map(::dec)
        if (p.size < 8) return null
        if (!QuarkWeb.loggedIn) {
            Log.w(TAG, "没有夸克网页版登录，分享转存不了")
            return null
        }
        val (pwdId, code, dir, fid, token) = p
        val size = p[5].toLongOrNull() ?: 0L
        val name = p[6]
        val share = QuarkWeb.openShare(pwdId, code)
        val app = QuarkWeb.ensureFolder("0", QuarkWeb.APP_FOLDER)
        val sub = QuarkWeb.ensureFolder(app, QuarkWeb.folderName(p[7]))
        val saved = QuarkWeb.list(sub).firstOrNull { it.name == name && it.size == size }?.fid
            ?: QuarkWeb.save(share, QuarkWeb.ShareFile(fid, name, false, size, token), dir, sub)
            ?: return null
        if (Quark.loggedIn) runCatching { Quark.link("quark://$saved?q=super") }.getOrNull()?.let { return it }
        return QuarkWeb.link(QuarkWeb.episodeLink(saved))
    }
}
