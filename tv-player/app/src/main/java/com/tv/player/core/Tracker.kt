package com.tv.player.core

import com.tv.player.data.VodItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** Looks up how many episodes each favorite has now, so the "我的" tab can say which ones got new episodes. */
object Tracker {
    private const val EVERY_MS = 3600_000L

    fun due(): Boolean = System.currentTimeMillis() - Store.trackChecked() > EVERY_MS

    /** Number of favorites with episodes the user has not seen. Failed lookups leave the old figures alone. */
    suspend fun check(force: Boolean = false): Int {
        val favorites = Library.favorites()
        if (favorites.isEmpty()) return 0
        if (force || due()) {
            // The sources searches use (switched-off ones excluded), so the count comes from the same source the player opens.
            val sources = Store.sources().associateBy { it.key }
            val gate = Semaphore(4)
            val answered = coroutineScope {
                favorites.map { f ->
                    async(Dispatchers.IO) {
                        gate.withPermit { episodeCount(f.items, sources)?.also { Library.updateLatest(f.key, it) } != null }
                    }
                }.awaitAll()
            }
            // Offline: try again next time instead of waiting an hour.
            if (answered.any { it }) Store.markTrackChecked()
        }
        return newCount()
    }

    fun newCount(): Int = Library.favorites().count { it.fresh > 0 }

    /** The episode count from the first source that answers, which is also the one the player opens first. */
    private suspend fun episodeCount(items: List<VodItem>, sources: Map<String, com.tv.player.data.VodSource>): Int? {
        for (item in items.take(3)) {
            val source = sources[item.sourceKey] ?: continue
            val detail = try {
                withTimeoutOrNull(8_000) { Cms.detail(source, item.id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: continue
            return countOf(detail)
        }
        return null
    }

    fun countOf(detail: com.tv.player.data.VodDetail): Int = detail.lines.maxOfOrNull { it.episodes.size } ?: 0
}
