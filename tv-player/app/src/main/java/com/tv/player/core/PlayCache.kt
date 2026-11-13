package com.tv.player.core

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * The disk cache behind on-demand playback: what the player downloads (and what is loaded ahead for the next episode) stays
 * on disk, so going back, replaying, coming back to an episode or trying a line again does not download it twice. The oldest
 * is dropped first once the limit (about [MAX_BYTES]) is reached. One instance for the whole process: the cache folder allows only one.
 */
@UnstableApi
object PlayCache {
    const val MAX_BYTES = 1024L * 1024 * 1024 // 1 GB：空闲预缓存会往后存十分钟
    private const val MIN_BYTES = 300L * 1024 * 1024

    /**
     * [MAX_BYTES]，但不超过可用空间的四分之一（存储小的盒子），最少还是原来的 300 MB。可用空间要加上缓存自己已经占的，
     * 不然存满之后下次启动算出来的上限一次比一次小；空间够大时不用去数缓存目录。
     */
    private fun limit(dir: File): Long {
        val free = dir.usableSpace
        if (free / 4 >= MAX_BYTES) return MAX_BYTES
        val used = File(dir, "play").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return ((free + used) / 4).coerceIn(MIN_BYTES, MAX_BYTES)
    }

    @Volatile
    private var cache: SimpleCache? = null

    @Volatile
    private var db: StandaloneDatabaseProvider? = null

    /** 播放缓存和离线下载共用一个数据库实例（它们用同一个数据库文件，Media3 要求只开一个）。 */
    fun database(context: Context): StandaloneDatabaseProvider = db ?: synchronized(this) {
        db ?: StandaloneDatabaseProvider(context.applicationContext).also { db = it }
    }

    fun get(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.applicationContext.cacheDir, "play"),
            LeastRecentlyUsedCacheEvictor(limit(context.applicationContext.cacheDir)),
            database(context),
        ).also { cache = it }
    }

    /** What the cache holds now, for showing in the settings; 0 before the first playback. */
    fun usedBytes(): Long = cache?.cacheSpace ?: 0L
}
