package com.tv.player.ui

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.tv.player.core.FallbackLatch
import com.tv.player.core.ParallelRanges
import com.tv.player.core.PrefetchPolicy
import com.tv.player.core.RangeFailure
import com.tv.player.core.RangeStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Executor

/**
 * The network end of the disk cache for a video file: reads it as pieces over several connections (HTTP Range) and hands
 * the bytes on in order, for lines that limit each connection. Whatever [eligible] refuses, or a host that has failed
 * before, is read plainly.
 *
 * Gives up parallel reading by itself and carries on from the same byte over one connection when: the server does not
 * answer Range properly, a piece fails (403, 429, refused, timed out), or the first piece shows the line is fast.
 */
@UnstableApi
class RangedDataSource(
    private val upstream: DataSource.Factory,
    private val eligible: (Uri) -> Boolean,
    private val latch: FallbackLatch,
    private val executor: Executor,
    private val log: (String) -> Unit = {},
) : DataSource {
    private val listeners = ArrayList<TransferListener>()
    private var spec: DataSpec? = null
    private var specEnd = C.LENGTH_UNSET.toLong()
    // What getUri / getResponseHeaders report, taken when the answer came: the connections themselves are closed by other threads.
    private var openedUri: Uri? = null
    private var openedHeaders: Map<String, List<String>> = emptyMap()
    private var single: DataSource? = null
    private var ranges: ParallelRanges? = null

    // The size of the file as the first answer gave it: what every later answer for it must agree with.
    private var probeTotal: Long? = null

    // Set when carrying on over one connection failed: every read after that fails the same way, not as an end of the file.
    private var failure: IOException? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        specEnd = if (dataSpec.length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else dataSpec.position + dataSpec.length
        val url = dataSpec.uri.toString()
        val usable = dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET && dataSpec.httpBody == null &&
            (dataSpec.uri.scheme.equals("http", true) || dataSpec.uri.scheme.equals("https", true)) &&
            eligible(dataSpec.uri) && !latch.tripped(url) && !latch.skipped(url)
        if (!usable) return openSingle(dataSpec.position)

        val chunk = PrefetchPolicy.CHUNK_BYTES.toLong()
        // The first piece doubles as the probe: its answer says whether Range works and how big the file is.
        val first = create()
        try {
            first.open(dataSpec.buildUpon().setLength(if (dataSpec.length == C.LENGTH_UNSET.toLong()) chunk else minOf(chunk, dataSpec.length)).build())
        } catch (e: IOException) {
            runCatching { first.close() }
            // Only an answer from the server (403, 429, 416...) can be about the Range request. A connection that could not
            // be made, or timed out, or was cut off by a seek, would fail the same way for a plain request: no second try
            // (a dead line must fail as fast as it did), and nothing to hold against the host.
            if (e !is HttpDataSource.InvalidResponseCodeException) throw e
            if (e.responseCode == 404 || e.responseCode == 410) {
                latch.skip(url) // this file is gone: the plain request will say so
            } else {
                latch.trip(url)
                log("range request refused, one connection from now on for ${FallbackLatch.hostOf(url)}: ${e.message}")
            }
            return openSingle(dataSpec.position)
        }
        val total = PrefetchPolicy.contentRangeTotal(first.responseHeaders)
        // The first piece goes straight to the player: it must begin where the player asked it to.
        val probeStart = PrefetchPolicy.contentRangeStart(first.responseHeaders)
        if (total == null || (probeStart != null && probeStart != dataSpec.position)) {
            runCatching { first.close() }
            // This file is served whole whatever is asked: not a reason to distrust the host for others.
            latch.skip(url)
            log("no Range support for this file, one connection")
            return openSingle(dataSpec.position)
        }
        val until = if (specEnd == C.LENGTH_UNSET.toLong()) total else minOf(total, specEnd)
        if (until <= dataSpec.position) {
            runCatching { first.close() }
            return openSingle(dataSpec.position)
        }
        openedUri = first.uri
        openedHeaders = first.responseHeaders
        probeTotal = total
        ranges = try {
            ParallelRanges(
                source = { from, to -> openPiece(dataSpec, from, to, total) },
                executor = executor,
                from = dataSpec.position,
                until = until,
                chunkBytes = PrefetchPolicy.CHUNK_BYTES,
                parallel = PrefetchPolicy.PARALLEL,
                head = Piece(first),
                fastBytesPerSec = PrefetchPolicy.FAST_BYTES_PER_SEC,
            )
        } catch (e: Exception) {
            runCatching { first.close() }
            throw e
        }
        return until - dataSpec.position
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        failure?.let { throw it }
        single?.let { return it.read(buffer, offset, length) }
        val r = ranges ?: return C.RESULT_END_OF_INPUT
        return try {
            r.read(buffer, offset, length)
        } catch (e: RangeFailure) {
            val url = spec!!.uri.toString()
            if (e.fast) {
                // Only for this open: a seek probes again (the line may be slower by then).
                log("one connection is fast enough for now")
            } else if (e.noGain) {
                latch.skip(url)
                log("extra connections gain nothing for this file (the line shares its speed), one connection")
            } else {
                latch.trip(url)
                log("a piece failed at ${e.position}, back to one connection for ${FallbackLatch.hostOf(url)}: ${e.cause?.message}")
            }
            r.close()
            r.awaitIdle(IDLE_WAIT_MS)
            try {
                openSingleAfterPieces(e.position)
            } catch (failed: IOException) {
                // What was handed over to is not the file the pieces came from: the next open reads this file plainly.
                if (failed is Mismatch) latch.skip(url)
                failure = failed
                throw failed
            }
            ranges = null
            single!!.read(buffer, offset, length)
        }
    }

    override fun getUri(): Uri? = openedUri ?: spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = openedHeaders

    override fun close() {
        try {
            // Give the pieces' connections a moment to close: a seek opens the next ones at once, and a server that
            // allows few connections must not see the old and the new together.
            ranges?.let {
                it.close()
                it.awaitIdle(CLOSE_WAIT_MS)
            }
            single?.close()
        } finally {
            ranges = null
            single = null
            failure = null
            probeTotal = null
            openedUri = null
            openedHeaders = emptyMap()
            spec = null
        }
    }

    private fun create(): DataSource = upstream.createDataSource().also { s -> listeners.forEach(s::addTransferListener) }

    /**
     * The connection that carries on after the pieces. A server that allows one connection at a time may not yet have
     * noticed that theirs are closed: a busy answer (429, 403, 503) is tried once more after a moment.
     */
    private fun openSingleAfterPieces(position: Long): Long = try {
        openSingle(position, verifyAgainst = probeTotal)
    } catch (e: HttpDataSource.InvalidResponseCodeException) {
        if (e.responseCode !in busyAnswers) throw e
        try {
            Thread.sleep(BUSY_RETRY_MS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException()
        }
        openSingle(position, verifyAgainst = probeTotal)
    }

    /**
     * One connection from [position] to the end of what was asked for. When [verifyAgainst] is given (carrying on after
     * parallel reading), the answer must be for the same file as the pieces were: where it begins and how big it is.
     */
    private fun openSingle(position: Long, verifyAgainst: Long? = null): Long {
        val base = spec!!
        val s = create()
        val length = try {
            s.open(base.buildUpon().setPosition(position).setLength(if (specEnd == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else specEnd - position).build()).also { opened ->
                if (verifyAgainst != null) {
                    if (PrefetchPolicy.contentRangeStart(s.responseHeaders) != null) {
                        checkAnswer(s, position, verifyAgainst)
                    } else if (specEnd == C.LENGTH_UNSET.toLong() && opened != C.LENGTH_UNSET.toLong() && position + opened != verifyAgainst) {
                        // An answer without Content-Range (the data source skipped to the position itself): what is left
                        // must be what is left of the file the pieces came from.
                        throw Mismatch("$opened bytes left after $position, the file was $verifyAgainst bytes")
                    }
                }
            }
        } catch (e: IOException) {
            runCatching { s.close() }
            throw e
        }
        single = s
        openedUri = s.uri
        openedHeaders = s.responseHeaders
        return length
    }

    private fun openPiece(base: DataSpec, from: Long, to: Long, total: Long): RangeStream {
        val s = create()
        try {
            s.open(base.buildUpon().setPosition(from).setLength(to - from).build())
            // The data source does not check where a 206 begins: bytes from the wrong place would be played as they are.
            // The first answer proved that this server says where its answers begin: one that does not now is another server
            // (or a node that ignores Range, and would first skip the $from bytes before it).
            checkAnswer(s, from, total)
        } catch (e: IOException) {
            runCatching { s.close() }
            throw e
        }
        return Piece(s)
    }

    /** The answer to a Range request must begin at [from] and describe a file of [total] bytes, or it is not the same bytes. */
    private fun checkAnswer(s: DataSource, from: Long, total: Long) {
        val start = PrefetchPolicy.contentRangeStart(s.responseHeaders)
        val size = PrefetchPolicy.contentRangeTotal(s.responseHeaders)
        if (start == null || size == null) throw Mismatch("no Content-Range in the answer for bytes from $from")
        if (start != from) throw Mismatch("asked for bytes from $from, the answer begins at $start")
        // Another size than the first answer reported: another version of the file (a mirror that is not in step).
        if (size != total) throw Mismatch("the file is $size bytes here, $total in the first answer")
    }

    /** An answer that is not for the bytes (or the file) that were asked for. */
    private class Mismatch(message: String) : IOException(message)

    private class Piece(private val source: DataSource) : RangeStream {
        override fun read(buffer: ByteArray, offset: Int, length: Int) = source.read(buffer, offset, length)
        override fun close() = source.close()
    }

    class Factory(
        private val upstream: DataSource.Factory,
        private val eligible: (Uri) -> Boolean,
        private val latch: FallbackLatch,
        private val executor: Executor,
        private val log: (String) -> Unit = {},
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = RangedDataSource(upstream, eligible, latch, executor, log)
    }

    private companion object {
        /** How long the pieces' connections get to close before the one connection that carries on is opened. */
        const val IDLE_WAIT_MS = 1_000L

        /** Answers that say "busy" rather than "no", for the connection that carries on, and how long it waits to ask again. */
        val busyAnswers = setOf(403, 429, 503)
        const val BUSY_RETRY_MS = 300L

        /** The same on a plain close (a seek, the end): short, the loader is waiting. */
        const val CLOSE_WAIT_MS = 200L
    }
}
