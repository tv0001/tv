package com.tv.player.core

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface RangeStream {
    /** Like InputStream.read: bytes read, or -1 at the end. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun close()
}

fun interface RangeSource {
    /** Opens bytes [from, until) of the file on a connection of its own. */
    @Throws(IOException::class)
    fun open(from: Long, until: Long): RangeStream
}

/**
 * Parallel reading stopped at [position], the first byte not yet delivered. The caller carries on from there on one
 * connection. Two reasons are no error: [fast], the first piece came so quickly that one connection is enough for this
 * file, and [noGain], the connections turned out to share one limited speed, so more of them only slow each other.
 */
class RangeFailure(val position: Long, val fast: Boolean, cause: Throwable?, val noGain: Boolean = false) :
    IOException("parallel ranges stopped at $position", cause)

/**
 * Reads bytes [from, until) of one file as pieces of [chunkBytes], up to [parallel] pieces downloading at once, and hands
 * them out in order, as soon as the next bytes are there (a piece is not waited for as a whole).
 *
 * The first piece starts alone, the others once it has run for a while: that gives its speed on one connection to compare
 * with its speed while the others download (see [PrefetchPolicy.MIN_GAIN]). Without a gain the others are stopped and
 * the first piece is finished alone, then [RangeFailure] hands over.
 *
 * A piece that fails is reported when the reader gets to it, after everything before it was delivered: [RangeFailure]
 * says where. [close] cancels the rest; a connection is given up at its next read, and is never closed from another thread.
 *
 * [head], when given, is a stream already open on the first piece.
 */
class ParallelRanges(
    private val source: RangeSource,
    private val executor: Executor,
    from: Long,
    until: Long,
    private val chunkBytes: Int,
    private val parallel: Int,
    private val head: RangeStream? = null,
    private val fastBytesPerSec: Long = Long.MAX_VALUE,
    private val nanos: () -> Long = System::nanoTime,
    private val skipBytes: Int = PrefetchPolicy.SKIP_BYTES,
    private val soloBytes: Int = PrefetchPolicy.SOLO_BYTES,
    private val checkBytes: Int = PrefetchPolicy.CHECK_BYTES,
    private val minGain: Double = PrefetchPolicy.MIN_GAIN,
    private val minSoloNanos: Long = PrefetchPolicy.MIN_SOLO_MS * 1_000_000L,
    private val steadyRatio: Double = PrefetchPolicy.STEADY_RATIO,
) {
    private class Chunk(val start: Long, val length: Int) {
        // Allocated by the thread that downloads the piece, when it starts: a big file has thousands of them.
        var buf: ByteArray? = null
        var written = 0
        var flowing = false // its first bytes have arrived
        var error: Throwable? = null
        var bytesPerSec = 0L
    }

    private enum class HandOver { FAST, NO_GAIN }

    /** What the check of the extra connections found (null: not judged, yet or at all). */
    enum class GainVerdict { KEPT, NO_GAIN }

    @Volatile
    var verdict: GainVerdict? = null
        private set

    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val chunks: List<Chunk>
    private var cur = 0
    private var curOff = 0
    private var started = 0
    private var running = 0
    private var handOver: HandOver? = null

    // The first piece that failed: the reader will stop at it and the rest is read over one connection. Nothing more is
    // started, and the pieces behind it that are running stop.
    @Volatile
    private var failedIdx = Int.MAX_VALUE

    // The first piece's speed alone (marks A to B) and then, once the others are really transferring, with them (C on).
    private var othersAllowed = false
    private var aBytes = -1
    private var aAt = 0L
    private var mBytes = -1
    private var mAt = 0L
    private var bBytes = -1
    private var bAt = 0L
    private var flowingOthers = 0
    private var cPending = false
    private var cBytes = -1
    private var cAt = 0L
    private var decided = false
    private var fastAlone = false

    // Which piece is the one being timed against the others: the first, or (when its window alone never gave a steady
    // reading) the second, with the first piece's whole-piece speed, which it had the line to itself for, as the reference.
    private var measureIdx = 0
    private var aloneRate = 0.0 // bytes per ns, one connection alone

    @Volatile
    private var noGain = false

    @Volatile
    private var closed = false

    /** The first byte not yet delivered. */
    var position = from
        private set

    init {
        require(until > from && chunkBytes > 0 && parallel > 0)
        val list = ArrayList<Chunk>()
        var at = from
        while (at < until) {
            val len = minOf(chunkBytes.toLong(), until - at).toInt()
            list += Chunk(at, len)
            at += len
        }
        chunks = list
        lock.withLock { startMore() }
    }

    @Throws(IOException::class)
    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        lock.lock()
        try {
            while (true) {
                if (closed || cur >= chunks.size) return -1
                handOver?.let {
                    handOver = null
                    throw RangeFailure(position, fast = it == HandOver.FAST, cause = null, noGain = it == HandOver.NO_GAIN)
                }
                val c = chunks[cur]
                val have = c.written - curOff
                if (have > 0) {
                    val n = minOf(have, length)
                    System.arraycopy(c.buf!!, curOff, buffer, offset, n)
                    curOff += n
                    position += n
                    if (curOff == c.length) finish(c)
                    return n
                }
                c.error?.let { throw RangeFailure(position, false, it) }
                try {
                    cond.await()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException()
                }
            }
        } finally {
            lock.unlock()
        }
    }

    /** Stops the pieces still downloading and drops what they hold. */
    fun close() = lock.withLock {
        closed = true
        chunks.forEach { it.buf = null }
        cond.signalAll()
    }

    /**
     * Waits until every connection of the pieces has been closed (after [close], each does so at its next read). A server
     * that allows one connection at a time would refuse the one that carries on, if it were opened before that. False on timeout.
     */
    fun awaitIdle(timeoutMs: Long): Boolean {
        // A loader cancelled by a seek arrives here with its interrupt flag set; waiting must not give up at once because
        // of it. The flag is put back afterwards.
        val wasInterrupted = Thread.interrupted()
        var interruptedAgain = false
        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            lock.withLock {
                while (running > 0) {
                    val left = deadline - System.nanoTime()
                    if (left <= 0) return false
                    try {
                        cond.awaitNanos(left)
                    } catch (e: InterruptedException) {
                        interruptedAgain = true // keep waiting out the time: the connections are still closing
                    }
                }
                return true
            }
        } finally {
            if (wasInterrupted || interruptedAgain) Thread.currentThread().interrupt()
        }
    }

    // Lock held: the piece the reader is on was delivered completely.
    private fun finish(c: Chunk) {
        c.buf = null
        // The piece being timed is the sample of the line: fast enough, or no gain from the others, and the rest of the file
        // is better off on one connection.
        if (cur == measureIdx && chunks.size > measureIdx + 1) {
            if (noGain) handOver = HandOver.NO_GAIN else if (fastAlone || c.bytesPerSec >= fastBytesPerSec) handOver = HandOver.FAST
        }
        cur++
        curOff = 0
        if (handOver == null) startMore() // handing over: no more connections for what is about to be given up
    }

    // Lock held.
    private fun startMore() {
        while (!closed && failedIdx == Int.MAX_VALUE && started < chunks.size && started < cur + parallel && (started == 0 || (othersAllowed && !noGain))) {
            val index = started
            val c = chunks[index]
            val first = if (index == 0) head else null
            started++
            running++
            try {
                executor.execute { work(c, index, first) }
            } catch (e: Exception) {
                running--
                runCatching { first?.close() }
                c.error = e
                failedIdx = minOf(failedIdx, index)
                cond.signalAll()
            }
        }
    }

    // Lock held: the first piece has [n] of its [length] bytes at time [now].
    private fun headProgress(n: Int, length: Int, now: Long) {
        if (aBytes < 0 && n >= skipBytes) {
            aBytes = n
            aAt = now
        } else if (aBytes >= 0 && mBytes < 0 && n >= aBytes + soloBytes / 2) {
            mBytes = n // the middle of the window alone
            mAt = now
        } else if (aBytes >= 0 && mBytes >= 0 && bBytes < 0 && n >= aBytes + soloBytes) {
            val aloneNanos = now - aAt
            val first = (mBytes - aBytes).toDouble() / maxOf(mAt - aAt, 1L)
            val second = (n - mBytes).toDouble() / maxOf(now - mAt, 1L)
            val steady = minOf(first, second) >= steadyRatio * maxOf(first, second)
            if (aloneNanos < minSoloNanos || !steady) {
                // Too quick to time (a line this fast, or bytes that were already waiting in a buffer), or the speed changed
                // inside the window (the buffer ran out and the line's own speed began). Time again from here, rather than
                // judge by it or let the others in on it.
                aBytes = n
                aAt = now
                mBytes = -1
            } else {
                bBytes = n
                bAt = now
                aloneRate = (n - aBytes).toDouble() / maxOf(aloneNanos, 1L)
                if ((n - aBytes).toDouble() * 1_000_000_000 / maxOf(aloneNanos, 1L) >= fastBytesPerSec) {
                    // One connection alone is fast enough: the others are not even started; the first piece is finished alone.
                    fastAlone = true
                    decided = true
                } else {
                    othersAllowed = true // the first piece has had its time alone: the others join
                    startMore()
                }
            }
        } else if (cPending && cBytes < 0) {
            // The others are transferring: the first piece is timed from this read on (bytes and time of the same read).
            cBytes = n
            cAt = now
        } else if (cBytes >= 0 && !decided && n >= cBytes + checkBytes) {
            decided = true
            judge(n, now)
        }
        if (n == length && !othersAllowed && !fastAlone) {
            // Done before a steady window could be had. If it came in fast enough there is nothing to join. Otherwise the
            // others start now, and the second piece is timed against them, with the speed of this whole piece (it had the
            // line to itself throughout) as the reference: the check is not skipped for want of a good window.
            if (chunks[0].bytesPerSec >= fastBytesPerSec) {
                fastAlone = true
            } else {
                othersAllowed = true
                if (chunks.size > 2 && parallel > 1) {
                    measureIdx = 1
                    aloneRate = chunks[0].bytesPerSec / 1_000_000_000.0
                }
                startMore()
            }
        }
    }

    // Lock held: the second piece (timed when the first one's window gave nothing steady) has [n] bytes at time [now].
    private fun secondProgress(n: Int, now: Long) {
        // Not before the piece is past its own start (a new connection begins slowly; the reference is a long-running one).
        if (cPending && cBytes < 0 && n >= skipBytes + soloBytes) {
            cBytes = n
            cAt = now
        } else if (cBytes >= 0 && !decided && n >= cBytes + checkBytes) {
            decided = true
            judge(n, now)
        }
    }

    // Lock held: the piece being timed went from [cBytes] to [n] bytes while the others transferred. Less than [minGain] of
    // its speed alone: they share one speed, and only slow each other.
    private fun judge(n: Int, now: Long) {
        val together = (n - cBytes).toDouble() / maxOf(now - cAt, 1L)
        if (together < minGain * aloneRate) {
            verdict = GainVerdict.NO_GAIN
            noGain = true
            cond.signalAll()
        } else {
            verdict = GainVerdict.KEPT
        }
    }

    // How many other pieces the one being timed has to be timed against.
    private fun wantedOthers() = minOf(parallel - 1, chunks.size - 1 - measureIdx)

    // Lock held: another piece has just delivered its first bytes. When all of them are transferring, the first piece is
    // timed from its next read on (their connections take a while to come up, and until then it still has the line to itself).
    private fun otherFlowing() {
        flowingOthers++
        if ((bBytes >= 0 || measureIdx > 0) && flowingOthers >= wantedOthers()) cPending = true
    }

    private fun work(c: Chunk, index: Int, first: RangeStream?) {
        var stream = first
        try {
            val buf = ByteArray(c.length)
            lock.withLock {
                if (closed) return
                c.buf = buf
            }
            val t0 = nanos()
            if (stream == null) {
                if (closed || (index > measureIdx && noGain) || index > failedIdx) return
                stream = source.open(c.start, c.start + c.length)
            }
            var n = 0
            while (n < c.length) {
                // Closed, or (one of the others) found to gain nothing, or behind a piece that failed: the reader stops there.
                if (closed || (index > measureIdx && noGain) || index > failedIdx) return
                val k = stream.read(buf, n, c.length - n)
                if (k < 0) throw EOFException("piece at ${c.start} ended after $n of ${c.length} bytes")
                n += k
                val now = nanos()
                lock.withLock {
                    // The speed goes in with the last bytes: the reader may take them, and judge the piece, at once.
                    if (n == c.length) c.bytesPerSec = c.length * 1_000_000_000L / maxOf(now - t0, 1L)
                    c.written = n
                    if (index == 0) headProgress(n, c.length, now) else if (index == 1 && measureIdx == 1) secondProgress(n, now)
                    if (index > measureIdx && !c.flowing) {
                        c.flowing = true
                        otherFlowing()
                    }
                    cond.signalAll()
                }
            }
        } catch (e: Throwable) {
            lock.withLock {
                c.error = e
                failedIdx = minOf(failedIdx, index)
                cond.signalAll()
            }
        } finally {
            runCatching { stream?.close() }
            lock.withLock {
                running--
                cond.signalAll()
            }
        }
    }
}
