package com.tv.player.ui

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.tv.player.core.PrefetchScheduler
import java.io.ByteArrayOutputStream

/**
 * Wraps what the player reads from and tells the [PrefetchScheduler] about it: each HLS playlist read (so it knows the
 * segments that follow), and each segment the player opens and closes (so it can fetch the ones after it, and see how
 * fast the line is). The bytes pass through unchanged.
 */
@UnstableApi
class PrefetchingDataSource(private val inner: DataSource, private val scheduler: PrefetchScheduler) : DataSource {
    private var url: String? = null
    private var finalUrl: String? = null
    private var startPosition = 0L
    private var playlist: ByteArrayOutputStream? = null
    private var mode: PrefetchScheduler.Mode? = null
    private var openLength = C.LENGTH_UNSET.toLong()
    private var bytes = 0L
    private var eof = false
    private var startedAt = 0L

    override fun addTransferListener(transferListener: TransferListener) = inner.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        url = dataSpec.uri.toString()
        finalUrl = null
        startPosition = dataSpec.position
        openLength = C.LENGTH_UNSET.toLong()
        playlist = if (dataSpec.position == 0L && isPlaylist(dataSpec.uri)) ByteArrayOutputStream() else null
        mode = if (playlist == null) safely { scheduler.onPlayerOpen(dataSpec.uri.toString()) } else null
        bytes = 0
        eof = false
        startedAt = SystemClock.elapsedRealtime()
        openLength = inner.open(dataSpec)
        // Where the answer came from (after redirects): the playlist's relative addresses are resolved against that.
        finalUrl = inner.uri?.toString()
        // Only now, with the player's own request answered, is anything fetched ahead.
        if (mode != null) safely { scheduler.onPlayerOpened(dataSpec.uri.toString()) }
        return openLength
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = inner.read(buffer, offset, length)
        if (n > 0) {
            bytes += n
            playlist?.let { if (it.size() + n > MAX_PLAYLIST_BYTES) playlist = null else it.write(buffer, offset, n) }
        } else if (n == C.RESULT_END_OF_INPUT) {
            eof = true
        }
        return n
    }

    override fun getUri(): Uri? = inner.uri

    override fun getResponseHeaders(): Map<String, List<String>> = inner.responseHeaders

    override fun close() {
        try {
            inner.close()
        } finally {
            val u = url
            if (u != null) {
                playlist?.takeIf { eof }?.let { text -> safely { scheduler.onPlaylist(u, finalUrl ?: u, text.toString(Charsets.UTF_8.name()), UriUtil::resolve) } }
                // A read that began part way (a retry) did not time the whole segment.
                val whole = startPosition == 0L && (eof || (openLength != C.LENGTH_UNSET.toLong() && bytes >= openLength))
                mode?.let { m -> safely { scheduler.onPlayerDone(u, m, bytes, whole, SystemClock.elapsedRealtime() - startedAt) } }
            }
            url = null
            finalUrl = null
            playlist = null
            mode = null
        }
    }

    class Factory(private val inner: DataSource.Factory, private val scheduler: PrefetchScheduler) : DataSource.Factory {
        override fun createDataSource(): DataSource = PrefetchingDataSource(inner.createDataSource(), scheduler)
    }

    private companion object {
        const val MAX_PLAYLIST_BYTES = 1_000_000

        /** Fetching ahead is an extra: whatever it throws, the player's own read goes on as if it were not there. */
        inline fun <T> safely(block: () -> T): T? = try {
            block()
        } catch (e: Exception) {
            null
        }

        /** As PlayActivity decides a stream is HLS: ".m3u8" anywhere in the address, not only at the end of its path. */
        fun isPlaylist(uri: Uri) = uri.toString().contains(".m3u8", ignoreCase = true)
    }
}
