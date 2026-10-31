package com.tv.player.core

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.scheduler.Requirements
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 离线下载（手机、平板）：用 Media3 自己的下载管理，m3u8 和 mp4 都能下。下载的内容放在单独的目录里，不会像播放缓存那样
 * 被自动清掉；默认只在 Wi-Fi（不计流量的网络）下载，下载页里可以改。播放时从这里读（见 CastActivity 的离线模式）。
 */
@UnstableApi
object Downloads {
    /** 一条下载的说明，存在下载请求的 data 里。 */
    class Info(val title: String, val episode: String, val pic: String) {
        fun toBytes(): ByteArray = JSONObject().put("t", title).put("e", episode).put("p", pic).toString().toByteArray()

        companion object {
            fun of(d: Download): Info = runCatching {
                JSONObject(String(d.request.data)).let { Info(it.optString("t"), it.optString("e"), it.optString("p")) }
            }.getOrDefault(Info("", "", ""))
        }
    }

    private lateinit var app: Context
    private var cache: SimpleCache? = null
    private var manager: DownloadManager? = null

    @Synchronized
    fun cache(context: Context): SimpleCache {
        app = context.applicationContext
        return cache ?: SimpleCache(File(app.filesDir, "downloads"), NoOpCacheEvictor(), PlayCache.database(app)).also { cache = it }
    }

    private fun http(): DataSource.Factory = DefaultDataSource.Factory(
        app,
        DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("User-Agent" to Http.UA))
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(20_000),
    )

    @Synchronized
    fun manager(context: Context): DownloadManager {
        manager?.let { return it }
        val c = cache(context)
        return DownloadManager(app, PlayCache.database(app), c, http(), Executors.newFixedThreadPool(4)).apply {
            maxParallelDownloads = 2
            requirements = requirementsNow()
        }.also { manager = it }
    }

    private fun requirementsNow() = Requirements(if (Store.downloadOnMetered()) Requirements.NETWORK else Requirements.NETWORK_UNMETERED)

    fun setDownloadOnMetered(context: Context, on: Boolean) {
        Store.setDownloadOnMetered(on)
        manager(context).requirements = requirementsNow()
    }

    /** 播放下载好的内容：先从下载目录读，没有的再走网络（不写回下载目录）。 */
    fun playbackSource(context: Context): DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache(context))
        .setUpstreamDataSourceFactory(http())
        .setCacheWriteDataSinkFactory(null)

    fun mediaItem(url: String): MediaItem = MediaItem.Builder().setUri(url).apply {
        if (url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
    }.build()

    fun all(context: Context): List<Download> {
        val list = ArrayList<Download>()
        manager(context).downloadIndex.getDownloads().use { c -> while (c.moveToNext()) list += c.download }
        return list.sortedByDescending { it.updateTimeMs }
    }

    fun usedBytes(): Long = cache?.cacheSpace ?: 0L
}
