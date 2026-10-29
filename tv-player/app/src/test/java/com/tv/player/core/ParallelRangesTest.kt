package com.tv.player.core

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** A clock whose first reading is 0 and every later one [elapsed] ns: the first thing timed took [elapsed] (use one piece at a time). */
private class ConstantElapsed(private val elapsed: Long) : () -> Long {
    private val calls = AtomicInteger()
    override fun invoke(): Long = if (calls.getAndIncrement() == 0) 0L else elapsed
}

class ParallelRangesTest {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
    private val file = ByteArray(10_000) { (it * 31 + it / 7).toByte() }

    @After
    fun stop() {
        pool.shutdownNow()
    }

    /** A file served in small reads, so a piece arrives in several steps. */
    private inner class Server(
        val readSize: Int = 64,
        val onOpen: (from: Long) -> Unit = {},
        val onRead: (from: Long) -> Unit = {},
        val failAt: Set<Long> = emptySet(),
        val shortAt: Set<Long> = emptySet(),
    ) : RangeSource {
        val opened = CopyOnWriteArrayList<Long>()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val closed = AtomicInteger()

        override fun open(from: Long, until: Long): RangeStream {
            opened += from
            onOpen(from)
            if (from in failAt) throw IOException("HTTP 403 at $from")
            return stream(from, until)
        }

        fun stream(from: Long, until: Long, countsAsActive: Boolean = true): RangeStream {
            if (countsAsActive) maxActive.accumulateAndGet(active.incrementAndGet()) { a, b -> maxOf(a, b) }
            var pos = from
            val end = if (from in shortAt) from + (until - from) / 2 else until
            return object : RangeStream {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    onRead(from)
                    if (pos >= end) return -1
                    val n = minOf(readSize.toLong(), length.toLong(), end - pos).toInt()
                    System.arraycopy(file, pos.toInt(), buffer, offset, n)
                    pos += n
                    return n
                }

                override fun close() {
                    if (countsAsActive) active.decrementAndGet()
                    closed.incrementAndGet()
                }
            }
        }
    }

    private fun ParallelRanges.drain(limit: Int = Int.MAX_VALUE): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(100)
        while (out.size() < limit) {
            val n = read(buf, 0, buf.size)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Reads until the end or until the parallel reading hands over or fails; what came, and why it stopped (null: the end). */
    private fun ParallelRanges.drainUntilStop(): Pair<ByteArray, RangeFailure?> {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(100)
        try {
            while (true) {
                val n = read(buf, 0, buf.size)
                if (n < 0) return out.toByteArray() to null
                out.write(buf, 0, n)
            }
        } catch (e: RangeFailure) {
            return out.toByteArray() to e
        }
    }

    // The defaults start the other pieces when the first one is done (the marks are bigger than the test pieces); tests
    // that need them earlier give small marks: [skip] bytes, then [solo] bytes alone, then [check] bytes together. The
    // minimum for the time alone and the steadiness of the two halves are switched off here (reads of a test take microseconds, their speed is noise); VirtualLine tests have their own.
    private fun ranges(
        server: RangeSource,
        from: Long = 0,
        until: Long = file.size.toLong(),
        chunk: Int = 1_000,
        parallel: Int = 3,
        head: RangeStream? = null,
        fast: Long = Long.MAX_VALUE,
        nanos: () -> Long = System::nanoTime,
        skip: Int = PrefetchPolicy.SKIP_BYTES,
        solo: Int = PrefetchPolicy.SOLO_BYTES,
        check: Int = PrefetchPolicy.CHECK_BYTES,
        minGain: Double = PrefetchPolicy.MIN_GAIN,
    ) = ParallelRanges(server, pool, from, until, chunk, parallel, head, fast, nanos, skip, solo, check, minGain, minSoloNanos = 0, steadyRatio = 0.0)

    @Test(timeout = 10_000)
    fun `the file comes out whole and in order`() {
        val r = ranges(Server())
        assertArrayEquals(file, r.drain())
        assertEquals(file.size.toLong(), r.position)
    }

    @Test(timeout = 10_000)
    fun `a range that starts inside the file and ends inside a piece`() {
        val r = ranges(Server(), from = 1_234, until = 7_777, chunk = 1_000)
        assertArrayEquals(file.copyOfRange(1_234, 7_777), r.drain())
    }

    @Test(timeout = 10_000)
    fun `a range smaller than one piece`() {
        val r = ranges(Server(), from = 10, until = 50, chunk = 1_000)
        assertArrayEquals(file.copyOfRange(10, 50), r.drain())
    }

    @Test(timeout = 10_000)
    fun `only the first piece starts at once, the others when it has run for a while`() {
        val release = CountDownLatch(1)
        val server = Server(onRead = { release.await(5, TimeUnit.SECONDS) })
        val r = ranges(server, chunk = 1_000, parallel = 3)
        Thread.sleep(200)
        // The first piece is stuck before its first byte: nobody else starts.
        assertEquals(listOf(0L), server.opened.toList())
        release.countDown()
        assertArrayEquals(file, r.drain())
    }

    @Test(timeout = 10_000)
    fun `a piece that finishes late holds nothing else back out of order`() {
        val release = CountDownLatch(1)
        val firstReads = AtomicInteger()
        // The first piece delivers a little, long enough to be timed alone, then sticks.
        val server = Server(readSize = 100, onRead = { from -> if (from == 0L && firstReads.incrementAndGet() > 4) release.await(5, TimeUnit.SECONDS) })
        val r = ranges(server, chunk = 1_000, parallel = 3, skip = 0, solo = 100, check = 1_000_000)
        // The later pieces are all downloaded while piece 0 is still stuck.
        while (server.opened.size < 3) Thread.sleep(5)
        assertEquals(setOf(0L, 1_000L, 2_000L), server.opened.toSet())
        release.countDown()
        assertArrayEquals(file, r.drain())
    }

    @Test(timeout = 10_000)
    fun `bytes are handed out while their piece is still downloading`() {
        val release = CountDownLatch(1)
        val server = Server(readSize = 100)
        // Piece 0 pauses after its first 100 bytes; the reader gets them without waiting for the other 900.
        var reads = 0
        val gated = RangeSource { from, until ->
            val inner = server.open(from, until)
            if (from != 0L) inner else object : RangeStream {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (reads++ == 1) release.await(5, TimeUnit.SECONDS)
                    return inner.read(buffer, offset, length)
                }

                override fun close() = inner.close()
            }
        }
        val r = ranges(gated, chunk = 1_000, parallel = 1)
        val buf = ByteArray(100)
        assertEquals(100, r.read(buf, 0, 100))
        assertArrayEquals(file.copyOfRange(0, 100), buf)
        release.countDown()
        assertArrayEquals(file.copyOfRange(100, file.size), r.drain())
    }

    @Test(timeout = 10_000)
    fun `three pieces are downloading at once and never more`() {
        // All three are held until they are all there, so the overlap is certain, not a matter of timing.
        val gate = CountDownLatch(3)
        val firstReads = AtomicInteger()
        val seen = ConcurrentHashMap.newKeySet<Long>()
        val server = Server(readSize = 100, onRead = { from ->
            val arrives = if (from == 0L) firstReads.incrementAndGet() == 4 else seen.add(from) && seen.size <= 2
            if (arrives) {
                gate.countDown()
                gate.await(5, TimeUnit.SECONDS)
            }
        })
        val submitted = AtomicInteger()
        val counting = java.util.concurrent.Executor { task ->
            submitted.incrementAndGet()
            pool.execute(task)
        }
        val r = ParallelRanges(server, counting, 0, file.size.toLong(), 1_000, 3, null, Long.MAX_VALUE, System::nanoTime, 0, 100, 1_000_000, 0.6, 0, 0.0)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(100)
        while (true) {
            val n = r.read(buf, 0, buf.size)
            if (n < 0) break
            out.write(buf, 0, n)
            // Pieces submitted are the ones delivered (a piece of 1000 bytes each) and at most three more: a start is made
            // inside the read that delivers a piece, so this holds at every point, whatever the timing.
            assertTrue("submitted ${submitted.get()} at ${r.position}", submitted.get() <= r.position / 1_000 + 3)
        }
        assertArrayEquals(file, out.toByteArray())
        assertEquals(0L, gate.count)
        assertTrue(server.maxActive.get() >= 3) // and three really were there at once
    }

    @Test(timeout = 10_000)
    fun `the piece being waited for is always among the downloading ones`() {
        val server = Server(readSize = 100)
        val r = ranges(server, chunk = 1_000, parallel = 2)
        r.drain(limit = 1_000)
        // Piece 0 is done and delivered: pieces 1 and 2 are the ones in flight or done by now, and not one more.
        while (server.opened.size < 3) Thread.sleep(5)
        assertTrue(1_000L in server.opened)
        assertTrue(2_000L in server.opened)
        assertFalse(3_000L in server.opened)
    }

    @Test(timeout = 10_000)
    fun `a failed piece is reported where it begins, after everything before it`() {
        val server = Server(failAt = setOf(3_000L))
        val r = ranges(server, chunk = 1_000)
        val (got, failure) = r.drainUntilStop()
        assertNotNull(failure)
        assertEquals(3_000L, failure!!.position)
        assertFalse(failure.fast)
        assertFalse(failure.noGain)
        assertTrue(failure.cause!!.message!!.contains("403"))
        assertArrayEquals(file.copyOfRange(0, 3_000), got)
        assertEquals(3_000L, r.position)
    }

    @Test(timeout = 10_000)
    fun `a failed first piece is reported at the start`() {
        val r = ranges(Server(failAt = setOf(0L)), chunk = 1_000)
        try {
            r.read(ByteArray(10), 0, 10)
            fail("no failure")
        } catch (e: RangeFailure) {
            assertEquals(0L, e.position)
        }
    }

    @Test(timeout = 10_000)
    fun `a piece cut short is a failure at the first byte it did not deliver`() {
        val server = Server(shortAt = setOf(2_000L), readSize = 100)
        val r = ranges(server, chunk = 1_000)
        val (got, failure) = r.drainUntilStop()
        assertEquals(2_500L, failure!!.position)
        assertArrayEquals(file.copyOfRange(0, 2_500), got)
    }

    @Test(timeout = 10_000)
    fun `an already open stream is used for the first piece`() {
        val server = Server()
        val r = ranges(server, chunk = 1_000, head = server.stream(0, 1_000, countsAsActive = false))
        assertArrayEquals(file, r.drain())
        assertFalse(0L in server.opened)
        assertTrue(1_000L in server.opened)
    }

    @Test(timeout = 10_000)
    fun `a first piece that came fast hands the rest to one connection`() {
        val r = ranges(Server(), chunk = 1_000, fast = 1)
        val got = r.drain(limit = 1_000)
        assertEquals(1_000, got.size)
        try {
            r.read(ByteArray(10), 0, 10)
            fail("no hand-over")
        } catch (e: RangeFailure) {
            assertTrue(e.fast)
            assertFalse(e.noGain)
            assertEquals(1_000L, e.position)
        }
    }

    @Test(timeout = 10_000)
    fun `the first piece at the threshold speed or above hands over, below it does not`() {
        val atThreshold = ranges(Server(readSize = 1_000), chunk = 1_000, parallel = 1, fast = 1_000, nanos = ConstantElapsed(1_000_000_000L))
        atThreshold.drain(limit = 1_000)
        try {
            atThreshold.read(ByteArray(10), 0, 10)
            fail("no hand-over at the threshold")
        } catch (e: RangeFailure) {
            assertTrue(e.fast)
        }
        val below = ranges(Server(readSize = 1_000), chunk = 1_000, parallel = 1, fast = 1_001, nanos = ConstantElapsed(1_000_000_000L))
        assertArrayEquals(file, below.drain())
    }

    @Test(timeout = 10_000)
    fun `a single piece is never a hand-over`() {
        val r = ranges(Server(), from = 0, until = 500, chunk = 1_000, fast = 1)
        assertArrayEquals(file.copyOfRange(0, 500), r.drain())
    }

    /**
     * Time that moves only as the first piece is read: [fastNs] per 100 bytes while it has the line to itself, [slowNs]
     * once another piece is transferring too. Nothing in a test depends on how fast the machine happens to be.
     */
    private inner class VirtualLine(
        private val fastNs: Long,
        private val slowNs: Long,
        private val holdAt: Int = 2_300,
        private val holdMs: Long = 3_000,
        private val burstUntil: Int = 0,
        private val burstNs: Long = 0,
    ) {
        private val clock = AtomicLong()
        // Counted down when another piece starts its second read: its first bytes are then known to the pipeline for certain.
        private val othersFlowing = CountDownLatch(1)
        val nanos: () -> Long = { clock.get() }

        fun head(): RangeStream = object : RangeStream {
            var pos = 0
            var held = false
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                // Held, once, until another piece has started to transfer (or [holdMs] have passed): the line is shared from then on.
                if (pos >= holdAt && !held) {
                    held = true
                    othersFlowing.await(holdMs, TimeUnit.MILLISECONDS)
                }
                val shared = othersFlowing.count == 0L
                // Bytes that were already waiting in a buffer come at once; after them, the line's own speed.
                clock.addAndGet(if (pos < burstUntil) burstNs else if (shared) slowNs else fastNs)
                val n = minOf(100, length, 8_000 - pos)
                if (n <= 0) return -1
                System.arraycopy(file, pos, buffer, offset, n)
                pos += n
                return n
            }

            override fun close() {}
        }

        /** The other pieces: they deliver at once, and tell that they have started. */
        val openedOthers = AtomicInteger()
        val others = RangeSource { from, until ->
            openedOthers.incrementAndGet()
            var pos = from.toInt()
            var reads = 0
            object : RangeStream {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (++reads == 2) othersFlowing.countDown()
                    val n = minOf(100, length, until.toInt() - pos)
                    if (n <= 0) return -1
                    System.arraycopy(file, pos, buffer, offset, n)
                    pos += n
                    return n
                }

                override fun close() {}
            }
        }
    }

    // 8000-byte first piece, 100-byte reads; 200 bytes skipped, 2000 alone, then 2000 together.
    private fun virtual(
        line: VirtualLine,
        minSoloNanos: Long = PrefetchPolicy.MIN_SOLO_MS * 1_000_000L,
        fast: Long = Long.MAX_VALUE,
        executor: java.util.concurrent.Executor = pool,
    ) = ParallelRanges(line.others, executor, 0, file.size.toLong(), 8_000, 3, line.head(), fast, line.nanos, 200, 2_000, 2_000, 0.6, minSoloNanos)

    @Test(timeout = 20_000)
    fun `connections that share one limited speed are given up, and the first piece is kept`() {
        // 4 ms per 100 bytes alone, 16 ms once the others transfer: a quarter of the speed.
        val r = virtual(VirtualLine(fastNs = 4_000_000, slowNs = 16_000_000))
        val (got, failure) = r.drainUntilStop()
        assertNotNull("the gain was not judged", failure)
        assertEquals(ParallelRanges.GainVerdict.NO_GAIN, r.verdict)
        assertTrue(failure!!.noGain)
        assertFalse(failure.fast)
        // The first piece was finished alone and delivered whole; the hand-over is at its end.
        assertEquals(8_000L, failure.position)
        assertArrayEquals(file.copyOfRange(0, 8_000), got)
        r.close()
        assertTrue(r.awaitIdle(5_000))
    }

    @Test(timeout = 20_000)
    fun `connections that each have a speed of their own are kept`() {
        val r = virtual(VirtualLine(fastNs = 4_000_000, slowNs = 4_000_000))
        val (got, failure) = r.drainUntilStop()
        assertEquals(null, failure)
        assertEquals(ParallelRanges.GainVerdict.KEPT, r.verdict) // judged, and kept
        assertArrayEquals(file, got)
    }

    @Test(timeout = 20_000)
    fun `a slowdown just under the bar is kept, just over it is given up`() {
        // 0.62 of the speed: more than the 0.6 bar. 0.5: less.
        val keep = virtual(VirtualLine(fastNs = 5_000_000, slowNs = 8_000_000)) // 5/8 = 0.625
        val (keptBytes, keptFailure) = keep.drainUntilStop()
        assertEquals(null, keptFailure)
        assertEquals(ParallelRanges.GainVerdict.KEPT, keep.verdict)
        assertArrayEquals(file, keptBytes)
        val drop = virtual(VirtualLine(fastNs = 5_000_000, slowNs = 10_000_000)) // 5/10 = 0.5
        assertTrue(drop.drainUntilStop().second!!.noGain)
        assertEquals(ParallelRanges.GainVerdict.NO_GAIN, drop.verdict)
    }

    @Test(timeout = 20_000)
    fun `a time alone too short to mean anything is no ground for giving up`() {
        // 1 ms per 100 bytes: 2000 bytes alone take 20 ms, under the 30 ms that a comparison needs. Bytes that were
        // already waiting in a buffer look like that: the window is started again, and the others join when the piece is done.
        // The first piece waits a moment, as in the other tests, for the others to join: they must not, while the window
        // is being timed again. (If they did, the comparison would run and find a line that shares its speed.)
        val r = virtual(VirtualLine(fastNs = 1_000_000, slowNs = 16_000_000, holdMs = 400))
        val (got, failure) = r.drainUntilStop()
        assertEquals(null, failure)
        assertEquals(null, r.verdict) // every window was too short to time: re-timed, never judged
        assertArrayEquals(file, got)
    }

    @Test(timeout = 20_000)
    fun `a line that is fast for the first piece alone never gets the extra connections`() {
        // 50 microseconds per 100 bytes: 2 MB/s, over the 1 MB/s bar, already while the first piece is alone.
        val line = VirtualLine(fastNs = 50_000, slowNs = 50_000, holdAt = Int.MAX_VALUE)
        // What is handed to the executor is what gets started: the others are submitted the moment they are let in.
        val submitted = AtomicInteger()
        val counting = java.util.concurrent.Executor { task ->
            submitted.incrementAndGet()
            pool.execute(task)
        }
        val r = virtual(line, minSoloNanos = 0, fast = 1_000_000, executor = counting)
        val (got, failure) = r.drainUntilStop()
        assertNotNull(failure)
        assertTrue(failure!!.fast)
        assertEquals(8_000L, failure.position) // the first piece, whole, then the hand-over
        assertArrayEquals(file.copyOfRange(0, 8_000), got)
        assertEquals(1, submitted.get()) // the first piece only: no other connection was ever started
    }

    @Test(timeout = 20_000)
    fun `an interrupted thread still waits for the connections to close, and stays interrupted`() {
        val release = CountDownLatch(1)
        val firstReads = AtomicInteger()
        val server = Server(readSize = 100, onRead = { from -> if (from != 0L || firstReads.incrementAndGet() > 4) release.await(5, TimeUnit.SECONDS) })
        val r = ranges(server, chunk = 1_000, parallel = 2, skip = 0, solo = 100, check = 1_000_000)
        while (server.opened.size < 2) Thread.sleep(5)
        r.close()
        val result = AtomicReference<Boolean?>(null)
        val stillInterrupted = AtomicReference<Boolean?>(null)
        val inside = CountDownLatch(1)
        val waiter = Thread {
            Thread.currentThread().interrupt() // what a loader cancelled by a seek looks like when it closes its data source
            inside.countDown()
            result.set(r.awaitIdle(5_000))
            stillInterrupted.set(Thread.currentThread().isInterrupted)
        }.apply { start() }
        inside.await(5, TimeUnit.SECONDS)
        Thread.sleep(200)
        // The connections are still open: an interrupted thread must be waiting, not have given up (and answered false).
        assertEquals(null, result.get())
        assertTrue(waiter.isAlive)
        release.countDown() // the connections can go now
        waiter.join(5_000)
        assertEquals(true, result.get())
        assertEquals(true, stillInterrupted.get())
    }

    @Test(timeout = 10_000)
    fun `a huge file is not allocated in advance`() {
        // 4 GB in 1 MB pieces: only the pieces being downloaded may hold memory (the test JVM has far less than 4 GB).
        val never = RangeSource { _, _ -> throw IOException("not now") }
        val r = ParallelRanges(never, pool, 0, 4L * 1024 * 1024 * 1024, 1 shl 20, 3)
        r.close()
        assertTrue(r.awaitIdle(5_000))
    }

    @Test(timeout = 10_000)
    fun `closing stops the downloads and every connection is closed`() {
        val server = Server(readSize = 10, onRead = { Thread.sleep(2) })
        val r = ranges(server, chunk = 1_000, parallel = 3, skip = 0, solo = 20, check = 1_000_000)
        r.read(ByteArray(10), 0, 10)
        while (server.opened.size < 2) Thread.sleep(2) // the others have joined
        r.close()
        assertEquals(-1, r.read(ByteArray(10), 0, 10))
        // Each connection notices at its next read; once none is left running, none can be started and none is open.
        assertTrue(r.awaitIdle(5_000))
        assertEquals(0, server.active.get())
        assertEquals(server.opened.size, server.closed.get())
    }

    @Test(timeout = 10_000)
    fun `after a close it can be waited for until every connection is closed`() {
        val server = Server(readSize = 10, onRead = { Thread.sleep(2) })
        val r = ranges(server, chunk = 1_000, parallel = 3)
        r.read(ByteArray(10), 0, 10)
        r.close()
        assertTrue(r.awaitIdle(5_000))
        assertEquals(0, server.active.get())
    }

    @Test(timeout = 10_000)
    fun `waiting for the connections gives up at the timeout`() {
        val release = CountDownLatch(1)
        val firstReads = AtomicInteger()
        val server = Server(readSize = 100, onRead = { from -> if (from != 0L || firstReads.incrementAndGet() > 4) release.await(5, TimeUnit.SECONDS) })
        val r = ranges(server, chunk = 1_000, parallel = 2, skip = 0, solo = 100, check = 1_000_000)
        while (server.opened.size < 2) Thread.sleep(5)
        r.close()
        assertFalse(r.awaitIdle(100))
        release.countDown()
        assertTrue(r.awaitIdle(5_000))
    }

    @Test(timeout = 10_000)
    fun `a reader blocked on a slow piece is woken by close`() {
        val release = CountDownLatch(1)
        val server = Server(onRead = { release.await(5, TimeUnit.SECONDS) })
        val r = ranges(server, chunk = 1_000)
        var result: Int? = null
        val reader = Thread { result = r.read(ByteArray(10), 0, 10) }.apply { start() }
        Thread.sleep(100)
        r.close()
        reader.join(3_000)
        assertFalse(reader.isAlive)
        assertEquals(-1, result)
        release.countDown()
    }

    @Test(timeout = 10_000)
    fun `an interrupted reader gets an interrupted-IO error, not a hang`() {
        val release = CountDownLatch(1)
        val server = Server(onRead = { release.await(5, TimeUnit.SECONDS) })
        val r = ranges(server, chunk = 1_000)
        var error: Throwable? = null
        val reader = Thread {
            try {
                r.read(ByteArray(10), 0, 10)
            } catch (e: Throwable) {
                error = e
            }
        }.apply { start() }
        Thread.sleep(100)
        reader.interrupt()
        reader.join(3_000)
        assertTrue(error is java.io.InterruptedIOException)
        release.countDown()
        r.close()
    }

    @Test(timeout = 10_000)
    fun `an executor that refuses work turns into a failure at the piece it was meant for`() {
        val refusing = java.util.concurrent.Executor { throw java.util.concurrent.RejectedExecutionException("down") }
        val r = ParallelRanges(Server(), refusing, 0, 5_000, 1_000, 3)
        try {
            r.read(ByteArray(10), 0, 10)
            fail("no failure")
        } catch (e: RangeFailure) {
            assertEquals(0L, e.position)
        }
    }

    @Test(timeout = 10_000)
    fun `a server that allows one connection at a time refuses the extra pieces and the failure is reported`() {
        val busy = AtomicInteger()
        val server = Server(
            onOpen = {
                if (busy.incrementAndGet() > 1) {
                    busy.decrementAndGet()
                    throw IOException("HTTP 429")
                }
            },
            readSize = 100,
        )
        val r = ranges(server, chunk = 1_000, parallel = 3)
        val (_, failure) = r.drainUntilStop()
        assertNotNull(failure)
        assertFalse(failure!!.fast)
        assertTrue(failure.cause!!.message!!.contains("429"))
    }

    @Test(timeout = 10_000)
    fun `a first piece that came fast starts no other piece, and the hand-over starts none either`() {
        // The first piece came fast: nobody has joined it, and when it is delivered the pipeline hands over without
        // starting the next one (the guard in finish). Only the head was ever submitted.
        val submitted = AtomicInteger()
        val counting = java.util.concurrent.Executor { task ->
            submitted.incrementAndGet()
            pool.execute(task)
        }
        val server = Server(readSize = 100)
        val r = ParallelRanges(server, counting, 0, file.size.toLong(), 1_000, 2, null, 1)
        r.drain(limit = 1_000)
        try {
            r.read(ByteArray(10), 0, 10)
            fail("no hand-over")
        } catch (e: RangeFailure) {
            assertTrue(e.fast)
        }
        assertEquals(1, submitted.get())
        r.close()
        assertTrue(r.awaitIdle(5_000))
    }

    @Test(timeout = 20_000)
    fun `a line too fast for the shortest window to time still gets no extra connections`() {
        // 20 microseconds per 100 bytes: 5 MB/s. A window of 2000 bytes lasts 0.4 ms, far under the 30 ms: it is timed
        // again and again, never long enough; the piece ends, comes in over the bar, and nobody is started.
        val line = VirtualLine(fastNs = 20_000, slowNs = 20_000, holdAt = Int.MAX_VALUE)
        val submitted = AtomicInteger()
        val counting = java.util.concurrent.Executor { task ->
            submitted.incrementAndGet()
            pool.execute(task)
        }
        val r = virtual(line, fast = 1_000_000, executor = counting)
        val (got, failure) = r.drainUntilStop()
        assertNotNull(failure)
        assertTrue(failure!!.fast)
        assertEquals(8_000L, failure.position)
        assertArrayEquals(file.copyOfRange(0, 8_000), got)
        assertEquals(1, submitted.get())
    }

    @Test(timeout = 20_000)
    fun `a line that gives a burst and then its own speed is not judged by the burst`() {
        // The first 1500 bytes come at 100 KB/s out of a buffer, the rest at 20 KB/s, the line's own speed; every
        // connection has that speed to itself (each is limited, the others lose nothing). A window that straddled the
        // change would read 42 KB/s alone, then 20 KB/s together, and find no gain. The halves of that window disagree:
        // it is timed again, over the line's own speed, and the extra connections are kept.
        val line = VirtualLine(fastNs = 5_000_000, slowNs = 5_000_000, holdAt = 4_300, burstUntil = 1_500, burstNs = 1_000_000)
        val r = virtual(line)
        val (got, failure) = r.drainUntilStop()
        assertEquals(null, failure)
        assertEquals(ParallelRanges.GainVerdict.KEPT, r.verdict)
        assertArrayEquals(file, got)
    }

    @Test(timeout = 10_000)
    fun `a hand-over after the others were let in starts no further piece`() {
        // The window alone is slow (25 KB/s), so the others join; the first piece then speeds up and ends over the bar
        // (the bar is 100 KB/s): FAST. Four pieces in all, two at a time: when the first is delivered the next one would
        // start, if the hand-over did not forbid it.
        val clock = java.util.concurrent.atomic.AtomicLong()
        val head = object : RangeStream {
            var pos = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                clock.addAndGet(if (pos < 600) 4_000_000 else 50_000)
                val n = minOf(100, length, 3_000 - pos)
                if (n <= 0) return -1
                System.arraycopy(file, pos, buffer, offset, n)
                pos += n
                return n
            }

            override fun close() {}
        }
        val others = RangeSource { from, until ->
            var pos = from.toInt()
            object : RangeStream {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val n = minOf(100, length, until.toInt() - pos)
                    if (n <= 0) return -1
                    System.arraycopy(file, pos, buffer, offset, n)
                    pos += n
                    return n
                }

                override fun close() {}
            }
        }
        val submitted = AtomicInteger()
        val counting = java.util.concurrent.Executor { task ->
            submitted.incrementAndGet()
            pool.execute(task)
        }
        val r = ParallelRanges(others, counting, 0, file.size.toLong(), 3_000, 2, head, 100_000, { clock.get() }, 0, 500, 1_000_000, 0.6, 0, 0.0)
        val (got, failure) = r.drainUntilStop()
        assertNotNull(failure)
        assertTrue(failure!!.fast)
        assertEquals(3_000L, failure.position)
        assertArrayEquals(file.copyOfRange(0, 3_000), got)
        assertEquals(2, submitted.get()) // the first piece and the one that joined it: not a third
        r.close()
        assertTrue(r.awaitIdle(5_000))
    }

    /**
     * A first piece whose speed changes every 1000 bytes (1 ms, then 9 ms per 100 bytes): no window of it is steady, so
     * the others only start when it is done. Its whole-piece speed is 20 KB/s. The second piece is then timed against
     * the others, at [secondNs] per 100 bytes; it waits (once) for the third to start.
     */
    private fun fallbackRun(secondNs: Long, startNs: Long = secondNs): Triple<ParallelRanges, Pair<ByteArray, RangeFailure?>, AtomicInteger> {
        val clock = AtomicLong()
        val submitted = AtomicInteger()
        val thirdFlowing = CountDownLatch(1)
        val head = object : RangeStream {
            var pos = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val n = minOf(100, length, 4_000 - pos)
                if (n <= 0) return -1
                clock.addAndGet(if ((pos / 1_000) % 2 == 0) 1_000_000L else 9_000_000L)
                System.arraycopy(file, pos, buffer, offset, n)
                pos += n
                return n
            }

            override fun close() {}
        }
        val others = RangeSource { from, until ->
            var pos = from.toInt()
            var reads = 0
            var held = false
            object : RangeStream {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    reads++
                    if (from == 8_000L && reads == 2) thirdFlowing.countDown()
                    if (from == 4_000L && pos >= 4_400 && !held) {
                        held = true
                        thirdFlowing.await(3, TimeUnit.SECONDS)
                    }
                    val n = minOf(100, length, until.toInt() - pos)
                    if (n <= 0) return -1
                    // Only the second piece moves the clock: it is the one timed. Its first 2000 bytes are its slow start.
                    if (from == 4_000L) clock.addAndGet(if (pos < 6_000) startNs else secondNs)
                    System.arraycopy(file, pos, buffer, offset, n)
                    pos += n
                    return n
                }

                override fun close() {}
            }
        }
        val counting = java.util.concurrent.Executor { task ->
            submitted.incrementAndGet()
            pool.execute(task)
        }
        val r = ParallelRanges(others, counting, 0, file.size.toLong(), 4_000, 3, head, Long.MAX_VALUE, { clock.get() }, 0, 2_000, 1_000, 0.6, 0)
        return Triple(r, r.drainUntilStop(), submitted)
    }

    @Test(timeout = 20_000)
    fun `when no window of the first piece is steady the second is timed against the others, and a shared line is found`() {
        // The second piece gets 4 KB/s while the others transfer, a fifth of the first piece's 20 KB/s alone.
        val (r, result, _) = fallbackRun(secondNs = 25_000_000)
        val (got, failure) = result
        assertNotNull("the others were never timed", failure)
        assertTrue(failure!!.noGain)
        assertEquals(ParallelRanges.GainVerdict.NO_GAIN, r.verdict)
        assertEquals(8_000L, failure.position) // the first two pieces whole, then the hand-over
        assertArrayEquals(file.copyOfRange(0, 8_000), got)
        r.close()
        assertTrue(r.awaitIdle(5_000))
    }

    @Test(timeout = 20_000)
    fun `when no window of the first piece is steady the second is timed against the others, and good connections are kept`() {
        // 25 KB/s for the second piece while the others transfer: above 0.6 of the first piece's 20 KB/s.
        val (r, result, _) = fallbackRun(secondNs = 4_000_000)
        val (got, failure) = result
        assertEquals(null, failure)
        assertEquals(ParallelRanges.GainVerdict.KEPT, r.verdict)
        assertArrayEquals(file, got)
    }

    @Test(timeout = 10_000)
    fun `no piece is started after one has failed, and those behind it stop`() {
        // Piece 1 fails at once. The first piece waits (on its fifth read) until that has happened, then goes on slowly:
        // when it is delivered the reader would start piece 3 (three at a time) if a failed piece did not forbid it.
        // Piece 2 is slow too: it is stopped long before its ten reads are done.
        val submitted = AtomicInteger()
        val counting = java.util.concurrent.Executor { task ->
            submitted.incrementAndGet()
            pool.execute(task)
        }
        val failedOpen = CountDownLatch(1)
        val firstReads = AtomicInteger()
        val secondReads = AtomicInteger()
        val server = Server(
            readSize = 100,
            failAt = setOf(1_000L),
            onOpen = { from -> if (from == 1_000L) failedOpen.countDown() },
            onRead = { from ->
                if (from == 0L) {
                    if (firstReads.incrementAndGet() == 5) failedOpen.await(5, TimeUnit.SECONDS)
                    Thread.sleep(2)
                }
                if (from == 2_000L) {
                    secondReads.incrementAndGet()
                    Thread.sleep(20)
                }
            },
        )
        val r = ParallelRanges(server, counting, 0, file.size.toLong(), 1_000, 3, null, Long.MAX_VALUE, System::nanoTime, 0, 100, 1_000_000, 0.6, 0, 0.0)
        val (got, failure) = r.drainUntilStop()
        assertNotNull(failure)
        assertEquals(1_000L, failure!!.position)
        assertArrayEquals(file.copyOfRange(0, 1_000), got)
        assertEquals(3, submitted.get())
        // Time enough for piece 2 to read all its ten reads (20 ms each) if nothing stopped it but the close below.
        Thread.sleep(400)
        assertTrue("the piece behind the failed one went on: ${secondReads.get()} reads", secondReads.get() < 10)
        r.close()
        assertTrue(r.awaitIdle(5_000))
    }

    @Test(timeout = 20_000)
    fun `the second piece is timed after its own slow start, not during it`() {
        // A new connection begins slowly (40 ms per 100 bytes for its first 2000 bytes) and then runs at 25 KB/s, above
        // 0.6 of the 20 KB/s the first piece had alone. Timed from its first bytes it would look like a line that shares
        // its speed (2.5 KB/s) and be given up.
        val (r, result, _) = fallbackRun(secondNs = 4_000_000, startNs = 40_000_000)
        val (got, failure) = result
        assertEquals(null, failure)
        assertEquals(ParallelRanges.GainVerdict.KEPT, r.verdict)
        assertArrayEquals(file, got)
    }
}
