package com.tv.player.core

object Updater {
    /** Downloads best.json and live.txt from [base], then refreshes every custom live list. Returns (anything updated, message). */
    suspend fun update(base: String, auto: Boolean = false): Pair<Boolean, String> {
        val dir = base.trim().let { if (it.endsWith("/")) it else "$it/" }
        val notes = ArrayList<String>()
        var any = false
        if (!auto) {
            Store.setPinned("config", false)
            Store.setPinned("live", false)
        }

        val skipConfig = auto && Store.isPinned("config")
        val skipLive = auto && Store.isPinned("live")
        val cfg = if (skipConfig) Http.Resp(0, "", dir) else Http.get(dir + "best.json", timeoutMs = 25_000)
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

        val live = if (skipLive) Http.Resp(0, "", dir) else Http.get(dir + "live.txt", timeoutMs = 25_000)
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
