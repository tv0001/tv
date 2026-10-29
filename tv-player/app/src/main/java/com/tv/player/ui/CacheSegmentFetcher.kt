package com.tv.player.ui

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import com.tv.player.core.PrefetchScheduler
import java.io.IOException

/**
 * Downloads a segment into the play cache under the key the player will look it up by (its address, whole file), over a
 * connection of its own. A segment the player holds open for writing cannot be written by two: the scheduler leaves the
 * one the player is on alone, and the player waits for one fetched here (see PlayActivity's cache flags).
 */
@UnstableApi
class CacheSegmentFetcher(private val cache: Cache, upstream: DataSource.Factory) : PrefetchScheduler.Fetcher {
    // No ignore-cache-on-error flag: a cache that cannot be written must fail the fetch, not download it into the void.
    private val factory = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstream)

    private fun key(url: String) = CacheKeyFactory.DEFAULT.buildCacheKey(DataSpec(Uri.parse(url)))

    override fun isCached(url: String): Boolean {
        val key = key(url)
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return length != C.LENGTH_UNSET.toLong() && cache.isCached(key, 0, length)
    }

    override fun hasAny(url: String): Boolean = cache.getCachedBytes(key(url), 0, C.LENGTH_UNSET.toLong()) > 0

    override fun fetch(url: String, token: PrefetchScheduler.CancelToken): PrefetchScheduler.Outcome {
        val source = factory.createDataSource()
        val writer = CacheWriter(source, DataSpec(Uri.parse(url)), null, null)
        token.onCancel { writer.cancel() }
        // A fetch cancelled half way earlier left a part: only the rest is downloaded now, and only that was timed.
        val before = cache.getCachedBytes(key(url), 0, C.LENGTH_UNSET.toLong())
        return try {
            writer.cache()
            // A span someone else holds for writing is read through without being kept: that is no fetch.
            if (isCached(url)) {
                // Whole, from the first byte, or it says little about the line (the set-up of a connection is most of a small rest).
                val total = ContentMetadata.getContentLength(cache.getContentMetadata(key(url)))
                if (before == 0L && total > 0) PrefetchScheduler.Outcome.Fetched(total) else PrefetchScheduler.Outcome.Done
            } else {
                PrefetchScheduler.Outcome.Failed("not in the cache afterwards", hostWide = false)
            }
        } catch (e: IOException) {
            when {
                token.cancelled -> PrefetchScheduler.Outcome.Cancelled
                // This file is gone, or the disk is the problem: nothing to hold against the host.
                e is HttpDataSource.InvalidResponseCodeException && (e.responseCode == 404 || e.responseCode == 410) ->
                    PrefetchScheduler.Outcome.Failed("HTTP ${e.responseCode}", hostWide = false)
                e is Cache.CacheException -> PrefetchScheduler.Outcome.Failed("cache: ${e.message}", hostWide = false)
                else -> PrefetchScheduler.Outcome.Failed(e.javaClass.simpleName + ": " + e.message)
            }
        } finally {
            // Whatever ended the download, the segment's write lock must go: the player may be waiting for it.
            runCatching { source.close() }
        }
    }
}
