package com.tv.player.core

import java.net.URL

object Resolve {
    private val direct = Regex("\\.(m3u8|mp4)(\\?|#|$)", RegexOption.IGNORE_CASE)
    private val quoted = Regex("[\"'`]([^\"'`\\s]+?\\.(?:m3u8|mp4)(?:\\?[^\"'`\\s]*)?)[\"'`]", RegexOption.IGNORE_CASE)

    /**
     * CMS "share" links are often HTML player pages. For those, take the first quoted .m3u8/.mp4
     * in the page and resolve it against the page URL. Direct stream URLs are returned as is.
     */
    suspend fun stream(playUrl: String): String? {
        // A drive file: its address is fetched now, as the drive hands out addresses that expire.
        if (QuarkWeb.isLink(playUrl)) return runCatching { QuarkWeb.link(playUrl) }.getOrNull()
        if (Quark.isLink(playUrl)) return runCatching { Quark.link(playUrl) }.getOrNull()
        // 网络存储（WebDAV / SMB）里的文件本身就是视频，地址不会过期
        if (NetStore.isLink(playUrl)) return NetStore.resolve(playUrl)
        // jar 爬虫的选集：调爬虫的 playerContent 换真实地址
        if (Spiders.isLink(playUrl)) return Spiders.play(playUrl)
        // jar 网盘资源站的夸克分享：转存到自己的网盘再放（见 PanShares）
        if (PanShares.isLink(playUrl)) return runCatching { PanShares.stream(playUrl) }.onFailure { android.util.Log.w("PanShares", "转存播放失败：$it") }.getOrNull()
        val u = runCatching { URL(playUrl) }.getOrNull() ?: return null
        val pathAndQuery = u.path + (u.query?.let { "?$it" } ?: "")
        if (direct.containsMatchIn(pathAndQuery)) return playUrl

        // Player pages are often slow; one retry keeps a single timeout from killing a line that would have worked.
        var r = Http.get(playUrl, timeoutMs = 10_000, limitBytes = 1_000_000)
        if (r.status != 200) r = Http.get(playUrl, timeoutMs = 10_000, limitBytes = 1_000_000)
        if (r.status != 200) return null
        val raw = quoted.find(r.text)?.groupValues?.get(1) ?: return null
        return runCatching { URL(URL(r.finalUrl.ifEmpty { playUrl }), raw.replace("\\/", "/")).toString() }.getOrNull()
    }
}
