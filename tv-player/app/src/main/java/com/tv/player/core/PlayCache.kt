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
 * is dropped first once [MAX_BYTES] is reached. One instance for the whole process: the cache folder allows only one.
 */
@UnstableApi
object PlayCache {
    const val MAX_BYTES = 300L * 1024 * 1024

    @Volatile
    private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.applicationContext.cacheDir, "play"),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { cache = it }
    }

    /** What the cache holds now, for showing in the settings; 0 before the first playback. */
    fun usedBytes(): Long = cache?.cacheSpace ?: 0L
}
