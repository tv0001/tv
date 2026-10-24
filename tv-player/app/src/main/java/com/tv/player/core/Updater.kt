package com.tv.player.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

object Updater {
    /** After the first usable answer, GitHub itself gets this long: its copy is never stale, so it is worth waiting a little. */
    private const val GRACE_MS = 3_000L
    private val mirrorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * One subscription file. With the default source it is asked from every mirror at once: the first usable answer wins,
     * but GitHub's own copy wins over it if it arrives within [GRACE_MS] (a mirror can keep an old copy for hours).
     */
    private suspend fun fetchFile(url: String, mirrored: Boolean): Http.Resp {
        if (!mirrored) return Http.get(url, timeoutMs = 25_000)
        val urls = AppUpdate.mirrorsOf(url)
        val raw = urls.indexOfFirst { it.startsWith("https://raw.githubusercontent.com/") }
        val answers = Channel<Pair<Int, Http.Resp>>(urls.size)
        val jobs = urls.mapIndexed { i, u -> mirrorScope.launch { answers.send(i to Http.get(u, timeoutMs = 25_000)) } }
        var chosen: Http.Resp? = null
        var failure: Http.Resp? = null
        var answered = 0
        while (answered < urls.size) {
            val (i, r) = if (chosen == null) answers.receive() else (withTimeoutOrNull(GRACE_MS) { answers.receive() } ?: break)
            answered++
            if (r.status != 200 || r.text.isEmpty()) {
                failure = failure ?: r
                continue
            }
            if (chosen == null || i == raw) chosen = r
            if (i == raw) break
        }
        jobs.forEach { it.cancel() }
        return chosen ?: failure ?: Http.Resp(0, "", url, "没有可用的镜像")
    }

    /** Downloads best.json and live.txt from [base], then refreshes every custom live list. Returns (anything updated, message). */
    suspend fun update(base: String, auto: Boolean = false): Pair<Boolean, String> {
        val dir = base.trim().let { if (it.endsWith("/")) it else "$it/" }
        val mirrored = dir == Store.DEFAULT_BASE
        val notes = ArrayList<String>()
        var any = false
        if (!auto) {
            Store.setPinned("config", false)
            Store.setPinned("live", false)
        }

        val skipConfig = auto && Store.isPinned("config")
        val skipLive = auto && Store.isPinned("live")
        val cfg = if (skipConfig) Http.Resp(0, "", dir) else fetchFile(dir + "best.json", mirrored)
        if (skipConfig) {
            notes += "点播源是手动导入的，已跳过"
        } else if (cfg.status == 200) {
            val n = runCatching { Store.parseSources(cfg.text).size }.getOrDefault(0)
            if (n > 0) {
                Store.saveConfig(cfg.text)
                notes += "点播源 $n 个"
                any = true
            } else {
                notes += "best.json 里没有可用的点播源"
            }
        } else {
            notes += "best.json 下载失败（${cfg.error ?: "HTTP ${cfg.status}"}）"
        }

        val live = if (skipLive) Http.Resp(0, "", dir) else fetchFile(dir + "live.txt", mirrored)
        if (skipLive) {
            notes += "直播列表是手动导入的，已跳过"
        } else if (live.status == 200) {
            val n = Live.parse(live.text).size
            if (n > 0) {
                Store.saveLive(live.text)
                notes += "直播频道 $n 个"
                any = true
            } else {
                notes += "live.txt 里没有频道"
            }
        } else {
            notes += "live.txt 下载失败（${live.error ?: "HTTP ${live.status}"}）"
        }

        // Extra CMS sites that the crawler verified with a real search; optional, so a missing file is not an error.
        val cms = fetchFile(dir + "cms.json", mirrored)
        if (cms.status == 200) {
            val n = runCatching { Store.parseSources(cms.text).size }.getOrDefault(0)
            if (n > 0) {
                Store.saveCms(cms.text)
                notes += "补充 CMS 站点 $n 个"
                any = true
            }
        }

        var customOk = 0
        val customs = Store.customLives()
        for (c in customs) {
            val r = Http.get(c.url, timeoutMs = 25_000)
            if (r.status == 200 && Live.parse(r.text).isNotEmpty()) {
                Store.updateCustomLiveText(c.url, r.text)
                customOk++
            }
        }
        if (customs.isNotEmpty()) notes += "自定义直播 $customOk/${customs.size} 个已刷新"

        if (any) Store.markUpdated()
        return any to (if (any) "已更新：" else "更新失败：") + notes.joinToString("；")
    }

    /** Auto-detects a TVBox config JSON or a TXT / M3U live list at [url] and imports it as the subscription copy. */
    suspend fun importUrl(url: String): Pair<Boolean, String> {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false to "请输入 http(s) 地址"
        val r = Http.get(u, timeoutMs = 25_000)
        if (r.status != 200) return false to "下载失败（${r.error ?: "HTTP ${r.status}"}）"
        val text = r.text.trim()
        if (text.startsWith("{")) {
            val n = runCatching { Store.parseSources(text).size }.getOrDefault(0)
            if (n == 0) return false to "这个配置里没有可用的 CMS 点播源（只支持 type 为 1 的站点）"
            Store.saveConfig(text)
            Store.setPinned("config", true)
            return true to "已导入点播源 $n 个（替换了订阅里的点播源，不会被自动更新覆盖；自定义源不受影响，点“立即更新”可换回订阅）"
        }
        val channels = Live.parse(text).size
        if (channels > 0) {
            Store.saveLive(text)
            Store.setPinned("live", true)
            return true to "已导入直播频道 $channels 个（不会被自动更新覆盖）"
        }
        return false to "无法识别：既不是 TVBox 配置 JSON，也不是 TXT / M3U 直播列表"
    }
}
