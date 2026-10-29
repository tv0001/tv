package com.tv.player.core

import com.tv.player.core.PrefetchScheduler.CancelToken
import com.tv.player.core.PrefetchScheduler.Mode
import com.tv.player.core.PrefetchScheduler.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL
import java.util.concurrent.Executor

/** Tasks queue up and run when the test says so, so "in flight" is exactly "submitted and not run yet". */
private class ManualExecutor : Executor {
    val tasks = ArrayDeque<Runnable>()
    override fun execute(command: Runnable) {
        tasks.addLast(command)
    }

    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }
}

private class FakeFetcher : PrefetchScheduler.Fetcher {
    val cached = HashSet<String>()
    val partial = HashSet<String>()
    val fetched = ArrayList<String>()
    var outcome: (String) -> Outcome = { Outcome.Done }
    override fun isCached(url: String) = url in cached
    override fun hasAny(url: String) = url in cached || url in partial
    override fun fetch(url: String, token: CancelToken): Outcome {
        fetched += url
        return outcome(url).also { if (it is Outcome.Done || it is Outcome.Fetched) cached += url }
    }
}

private class Rig(
    var allowed: (String) -> Boolean = { true },
    exclude: (String) -> Boolean = { false },
    segments: Int = 30,
) {
    val fetcher = FakeFetcher()
    val executor = ManualExecutor()
    val latch = FallbackLatch()
    val logs = ArrayList<String>()

    /** The scheduler's clock, in ns; a fetch in a test moves it by however long it is meant to have taken. */
    var nowNs = 0L
    val scheduler = PrefetchScheduler(fetcher, executor, latch, { allowed(it) }, exclude, { logs += it }, clock = { nowNs })
    val resolve = { base: String, ref: String -> URL(URL(base), ref).toString() }

    init {
        add("https://cdn.test/v/index.m3u8", segments)
    }

    fun playlist(count: Int, name: (Int) -> String = { "seg$it.ts" }) =
        "#EXTM3U\n#EXT-X-TARGETDURATION:4\n" + (0 until count).joinToString("") { "#EXTINF:3.5,\n${name(it)}\n" } + "#EXT-X-ENDLIST\n"

    fun add(url: String, count: Int, base: String = url, name: (Int) -> String = { "seg$it.ts" }) =
        scheduler.onPlaylist(url, base, playlist(count, name), resolve)

    fun seg(i: Int, base: String = "https://cdn.test/v/") = "${base}seg$i.ts"

    /** The player opens [url] and its request is answered. */
    fun open(url: String): Mode? = scheduler.onPlayerOpen(url)?.also { scheduler.onPlayerOpened(url) }

    /** The player reads segment [i] on its own, taking [ms] for 3.5 s of picture. */
    fun read(i: Int, ms: Long, base: String = "https://cdn.test/v/") {
        val mode = open(seg(i, base))!!
        scheduler.onPlayerDone(seg(i, base), mode, 350_000, true, ms)
    }

    fun slowLine() = read(0, 7_000) // half real time
}

class PrefetchSchedulerTest {
    @Test
    fun `nothing is fetched ahead until the line has shown itself slow`() {
        val rig = Rig()
        assertNull(rig.scheduler.speedRatio)
        rig.open(rig.seg(0))
        assertTrue(rig.executor.tasks.isEmpty())
    }

    @Test
    fun `a slow line gets the next segments fetched ahead, two at a time`() {
        val rig = Rig()
        rig.slowLine()
        assertEquals(0.5, rig.scheduler.speedRatio!!, 1e-9)
        assertEquals(Mode.DIRECT, rig.open(rig.seg(1)))
        // The window is segments 2..4, two connections: 4 waits for one of them.
        assertEquals(2, rig.executor.tasks.size)
        rig.executor.runAll()
        assertEquals(listOf(rig.seg(2), rig.seg(3), rig.seg(4)), rig.fetcher.fetched)
    }

    @Test
    fun `nothing is started before the player's own request has been answered`() {
        val rig = Rig()
        rig.slowLine()
        assertEquals(Mode.DIRECT, rig.scheduler.onPlayerOpen(rig.seg(1)))
        assertTrue(rig.executor.tasks.isEmpty())
        rig.scheduler.onPlayerOpened(rig.seg(1))
        assertEquals(2, rig.executor.tasks.size)
    }

    @Test
    fun `a request of the player that never got an answer starts nothing`() {
        val rig = Rig()
        rig.slowLine()
        rig.scheduler.onPlayerOpen(rig.seg(1)) // the open failed: onPlayerOpened is never called
        rig.scheduler.onPlayerDone(rig.seg(1), Mode.DIRECT, 0, false, 100)
        assertTrue(rig.executor.tasks.isEmpty())
    }

    @Test
    fun `what a jump leaves behind is cancelled before the player's request goes out`() {
        val rig = Rig()
        rig.slowLine()
        rig.open(rig.seg(1)) // 2 and 3 in flight
        rig.scheduler.onPlayerOpen(rig.seg(20)) // the jump: before its request is answered
        rig.executor.runAll()
        // 2 and 3 never started; nothing at the new place yet.
        assertTrue(rig.fetcher.fetched.isEmpty())
        rig.scheduler.onPlayerOpened(rig.seg(20))
        rig.executor.runAll()
        assertEquals(listOf(rig.seg(21), rig.seg(22), rig.seg(23)), rig.fetcher.fetched)
    }

    @Test
    fun `a fast line is left alone`() {
        val rig = Rig()
        rig.read(0, 1_000) // 3.5x real time
        rig.open(rig.seg(1))
        assertTrue(rig.executor.tasks.isEmpty())
        rig.open(rig.seg(2))
        assertTrue(rig.executor.tasks.isEmpty())
    }

    @Test
    fun `only the player's own downloads tell the speed, and a hit says nothing`() {
        val rig = Rig()
        rig.slowLine()
        rig.fetcher.cached += rig.seg(5)
        assertEquals(Mode.HIT, rig.open(rig.seg(5)))
        rig.scheduler.onPlayerDone(rig.seg(5), Mode.HIT, 350_000, true, 5) // from the disk: very fast
        assertEquals(0.5, rig.scheduler.speedRatio!!, 1e-9)
        // An unfinished read (a seek cut it off) says nothing either.
        rig.scheduler.onPlayerDone(rig.seg(1), Mode.DIRECT, 1_000, false, 10)
        assertEquals(0.5, rig.scheduler.speedRatio!!, 1e-9)
    }

    @Test
    fun `the speed is smoothed so one odd segment does not decide`() {
        val rig = Rig()
        rig.read(0, 1_750) // 2x: not slow, nothing is fetched ahead, so the next read is the player's alone as well
        rig.read(1, 7_000) // 0.5x
        assertEquals(1.25, rig.scheduler.speedRatio!!, 1e-9)
    }

    @Test
    fun `a segment being fetched ahead is joined by the player, not fetched twice`() {
        val rig = Rig()
        rig.slowLine()
        rig.open(rig.seg(1))
        assertEquals(Mode.JOINED, rig.open(rig.seg(2)))
        // The player moved on to 2 and has it open: the fetch of 2 that only now gets a thread is not made (the player
        // downloads it itself, and a second connection on it would only take from that); 3 stays, the window now reaches 5.
        rig.executor.runAll()
        assertEquals(listOf(rig.seg(3), rig.seg(4), rig.seg(5)), rig.fetcher.fetched)
    }

    @Test
    fun `a jump drops what was fetched ahead of the old place and fetches ahead of the new one`() {
        val rig = Rig()
        rig.slowLine()
        rig.open(rig.seg(1)) // 2 and 3 in flight, 4 waiting
        rig.open(rig.seg(20)) // seek
        rig.executor.runAll()
        assertEquals(listOf(rig.seg(21), rig.seg(22), rig.seg(23)), rig.fetcher.fetched)
        // Cancelling on purpose is no failure of the host.
        assertFalse(rig.latch.tripped("https://cdn.test/x"))
    }

    @Test
    fun `a jump backwards works the same`() {
        val rig = Rig()
        rig.slowLine()
        rig.open(rig.seg(10))
        rig.executor.runAll()
        rig.fetcher.fetched.clear()
        rig.fetcher.cached.clear()
        rig.open(rig.seg(2))
        rig.executor.runAll()
        assertEquals(listOf(rig.seg(3), rig.seg(4), rig.seg(5)), rig.fetcher.fetched)
    }

    @Test
    fun `the first failure on a host stops fetching ahead from it for good`() {
        val rig = Rig()
        rig.fetcher.outcome = { Outcome.Failed("HTTP 403") }
        rig.slowLine()
        rig.open(rig.seg(1))
        rig.executor.runAll()
        // 2 failed; 3 was in flight on the same host and was cancelled before it started; 4 was dropped.
        assertEquals(listOf(rig.seg(2)), rig.fetcher.fetched)
        assertTrue(rig.latch.tripped("https://cdn.test/v/seg9.ts"))
        rig.open(rig.seg(6))
        rig.open(rig.seg(7))
        assertTrue(rig.executor.tasks.isEmpty())
        assertTrue(rig.logs.any { it.contains("back to one connection") })
    }

    @Test
    fun `a thrown error counts as a failure too`() {
        val rig = Rig()
        rig.fetcher.outcome = { throw java.io.IOException("connection refused") }
        rig.slowLine()
        rig.open(rig.seg(1))
        rig.executor.runAll()
        assertTrue(rig.latch.tripped("https://cdn.test/v/seg1.ts"))
        assertTrue(rig.logs.any { it.contains("connection refused") })
    }

    @Test
    fun `another host is not held back by a failure on this one`() {
        val rig = Rig()
        rig.add("https://other.test/w/index.m3u8", 30)
        rig.fetcher.outcome = { if (it.startsWith("https://cdn.test")) Outcome.Failed("429") else Outcome.Done }
        rig.slowLine()
        rig.open(rig.seg(1))
        rig.executor.runAll()
        assertTrue(rig.latch.tripped("https://cdn.test/v/seg2.ts"))
        assertFalse(rig.latch.tripped("https://other.test/w/seg2.ts"))
        // The player switches line to the other host.
        rig.read(0, 7_000, "https://other.test/w/")
        rig.open(rig.seg(1, "https://other.test/w/"))
        rig.executor.runAll()
        assertTrue(rig.fetcher.fetched.contains(rig.seg(2, "https://other.test/w/")))
    }

    @Test
    fun `the segment the player has open is not fetched a second time`() {
        val rig = Rig()
        rig.slowLine()
        rig.open(rig.seg(3)) // open, not finished
        rig.open(rig.seg(1))
        rig.executor.runAll()
        // 4 was already being fetched for the first open, 2 is new, 5 was cancelled, 3 is the player's own.
        assertEquals(setOf(rig.seg(2), rig.seg(4)), rig.fetcher.fetched.toSet())
        assertEquals(2, rig.fetcher.fetched.size)
        assertFalse(rig.seg(3) in rig.fetcher.fetched)
    }

    @Test
    fun `what is already cached is not downloaded again`() {
        val rig = Rig()
        rig.slowLine()
        rig.fetcher.cached += rig.seg(2)
        rig.open(rig.seg(1))
        rig.executor.runAll()
        assertEquals(listOf(rig.seg(3), rig.seg(4)), rig.fetcher.fetched)
    }

    @Test
    fun `a stream that is not the one playing is neither fetched ahead for nor allowed to disturb the one that is`() {
        val rig = Rig()
        rig.add("https://cdn.test/next/index.m3u8", 30)
        rig.allowed = { it == "https://cdn.test/v/index.m3u8" }
        rig.slowLine()
        rig.open(rig.seg(1)) // 2, 3 in flight
        assertEquals(2, rig.executor.tasks.size)
        // The next episode is loaded ahead of time: its segments are read, nothing is fetched for it, nothing is cancelled.
        rig.open(rig.seg(0, "https://cdn.test/next/"))
        rig.open(rig.seg(1, "https://cdn.test/next/"))
        rig.executor.runAll()
        assertEquals(listOf(rig.seg(2), rig.seg(3), rig.seg(4)), rig.fetcher.fetched)
    }

    @Test
    fun `its reads do not count for the speed either`() {
        val rig = Rig()
        rig.add("https://cdn.test/next/index.m3u8", 30)
        rig.allowed = { it == "https://cdn.test/v/index.m3u8" }
        rig.slowLine()
        rig.read(0, 500, "https://cdn.test/next/") // fast, but not the stream playing
        assertEquals(0.5, rig.scheduler.speedRatio!!, 1e-9)
    }

    @Test
    fun `when the playing stream changes what was fetched ahead for the old one goes and the speed is measured again`() {
        val rig = Rig()
        rig.add("https://cdn.test/next/index.m3u8", 30)
        rig.slowLine()
        rig.open(rig.seg(1)) // 2, 3 in flight for the first stream
        rig.open(rig.seg(0, "https://cdn.test/next/")) // the next episode is the one playing now
        assertNull(rig.scheduler.speedRatio)
        rig.executor.runAll()
        assertTrue(rig.fetcher.fetched.isEmpty())
    }

    @Test
    fun `switched off, nothing is fetched at all`() {
        val rig = Rig(allowed = { false })
        rig.slowLine()
        rig.open(rig.seg(1))
        assertTrue(rig.executor.tasks.isEmpty())
        assertNull(rig.scheduler.speedRatio)
    }

    @Test
    fun `addresses the cache policy refuses are not fetched`() {
        val rig = Rig()
        rig.add("https://cdn.test/s/index.m3u8", 8) { if (it % 2 == 0) "seg$it.ts" else "seg$it.vtt" }
        rig.read(0, 7_000, "https://cdn.test/s/")
        rig.open("https://cdn.test/s/seg1.vtt")
        rig.executor.runAll()
        assertEquals(listOf("https://cdn.test/s/seg2.ts", "https://cdn.test/s/seg4.ts"), rig.fetcher.fetched)
    }

    @Test
    fun `the caller can exclude addresses`() {
        val rig = Rig(exclude = { it.endsWith("seg3.ts") })
        rig.slowLine()
        rig.open(rig.seg(1))
        rig.executor.runAll()
        assertEquals(listOf(rig.seg(2), rig.seg(4)), rig.fetcher.fetched)
    }

    @Test
    fun `releasing cancels what is in flight and ignores what comes after`() {
        val rig = Rig()
        rig.slowLine()
        rig.open(rig.seg(1))
        rig.scheduler.release()
        rig.executor.runAll()
        assertTrue(rig.fetcher.fetched.isEmpty())
        rig.open(rig.seg(5))
        assertTrue(rig.executor.tasks.isEmpty())
    }

    @Test
    fun `what is not a known segment is none of its business`() {
        val rig = Rig()
        assertNull(rig.open("https://cdn.test/v/key.bin"))
        assertNull(rig.open("https://elsewhere.test/seg1.ts"))
    }

    @Test
    fun `a playlist read again replaces the old list of its segments`() {
        val rig = Rig(segments = 3)
        rig.add("https://cdn.test/v/index.m3u8", 30)
        rig.slowLine()
        rig.open(rig.seg(1))
        assertEquals(2, rig.executor.tasks.size)
    }

    @Test
    fun `the cancel token tells a fetch to stop at once, even when told late`() {
        val token = CancelToken()
        var stopped = 0
        token.onCancel { stopped++ }
        token.cancel()
        token.cancel()
        assertEquals(1, stopped)
        assertTrue(token.cancelled)
        val late = CancelToken().also { it.cancel() }
        late.onCancel { stopped++ }
        assertEquals(2, stopped)
    }

    @Test
    fun `a playlist that came after a redirect is read against where it came from`() {
        val rig = Rig()
        // Asked for at one address, answered from another directory: its relative segments live there.
        rig.add("https://cdn.test/go/index.m3u8", 30, base = "https://edge.test/real/index.m3u8")
        assertNull(rig.scheduler.onPlayerOpen("https://cdn.test/go/seg1.ts"))
        val edge = "https://edge.test/real/"
        rig.read(0, 7_000, edge)
        rig.open(rig.seg(1, edge))
        rig.executor.runAll()
        assertEquals(listOf(rig.seg(2, edge), rig.seg(3, edge), rig.seg(4, edge)), rig.fetcher.fetched)
    }

    @Test
    fun `a master playlist after a redirect still makes its media playlists belong to the address the player was given`() {
        val rig = Rig()
        val master = "https://cdn.test/go/master.m3u8"
        rig.allowed = { it == master }
        val text = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow/index.m3u8\n"
        rig.scheduler.onPlaylist(master, "https://edge.test/real/master.m3u8", text, rig.resolve)
        // The player asks for the variant at the address resolved against where the master came from.
        rig.add("https://edge.test/real/low/index.m3u8", 30)
        val seg = { i: Int -> "https://edge.test/real/low/seg$i.ts" }
        rig.open(seg(0))
        rig.scheduler.onPlayerDone(seg(0), Mode.DIRECT, 350_000, true, 7_000)
        rig.open(seg(1))
        rig.executor.runAll()
        assertEquals(listOf(seg(2), seg(3), seg(4)), rig.fetcher.fetched)
    }

    @Test
    fun `a segment that is partly in the cache says nothing about the line`() {
        val rig = Rig()
        rig.slowLine()
        rig.fetcher.partial += rig.seg(1) // an earlier fetch was cancelled half way
        assertEquals(Mode.HIT, rig.open(rig.seg(1)))
        rig.scheduler.onPlayerDone(rig.seg(1), Mode.HIT, 150_000, true, 100)
        assertEquals(0.5, rig.scheduler.speedRatio!!, 1e-9)
    }

    @Test
    fun `a failure that is only about one file does not stop fetching from the host`() {
        val rig = Rig()
        rig.fetcher.outcome = { if (it.endsWith("seg2.ts")) Outcome.Failed("HTTP 404", hostWide = false) else Outcome.Done }
        rig.slowLine()
        rig.open(rig.seg(1))
        rig.executor.runAll()
        assertFalse(rig.latch.tripped("https://cdn.test/v/seg9.ts"))
        assertEquals(listOf(rig.seg(2), rig.seg(3), rig.seg(4)), rig.fetcher.fetched)
        assertTrue(rig.logs.any { it.contains("one file") })
    }

    @Test
    fun `reset cancels what is in flight and queued and measures the line again`() {
        val rig = Rig()
        rig.slowLine()
        rig.open(rig.seg(1))
        rig.scheduler.reset()
        assertNull(rig.scheduler.speedRatio)
        rig.executor.runAll()
        assertTrue(rig.fetcher.fetched.isEmpty())
        assertFalse(rig.latch.tripped("https://cdn.test/x"))
    }

    @Test
    fun `forgetting an old playlist keeps the segments a newer one also lists`() {
        val rig = Rig(segments = 3)
        val shared = "https://cdn.test/shared/seg0.ts"
        rig.add("https://cdn.test/a/index.m3u8", 3) { "../shared/seg$it.ts" }
        rig.add("https://cdn.test/b/index.m3u8", 3) { "../shared/seg$it.ts" }
        // 3 playlists are known (the rig's own, a, b); 11 more make 14: the two oldest, the rig's and a, fall out of the 12 kept.
        for (n in 0 until 11) rig.add("https://cdn.test/other$n/index.m3u8", 3)
        assertNotNull(rig.scheduler.onPlayerOpen(shared))
    }

    private fun Rig.fetchTakes(seconds: Long, bytes: Long = 350_000) {
        fetcher.outcome = {
            nowNs += seconds * 1_000_000_000L
            Outcome.Fetched(bytes)
        }
    }

    @Test
    fun `extra connections that each get what the player's own connection got are kept`() {
        val rig = Rig()
        rig.slowLine() // the player's own connection: 350 KB in 7 s
        rig.fetchTakes(7)
        rig.open(rig.seg(1))
        rig.executor.runAll()
        assertEquals(listOf(rig.seg(2), rig.seg(3), rig.seg(4)), rig.fetcher.fetched)
        assertFalse(rig.latch.tripped("https://cdn.test/v/seg9.ts"))
    }

    @Test
    fun `extra connections that share the speed of one are given up after the first`() {
        val rig = Rig()
        rig.slowLine()
        rig.fetchTakes(21) // a third of the speed: the line is shared, or all served over one connection
        rig.open(rig.seg(1))
        rig.executor.runAll()
        // 2 came in slowly (one batch); 3 too (two in a row: the line); 4 is dropped.
        assertEquals(listOf(rig.seg(2), rig.seg(3)), rig.fetcher.fetched)
        assertTrue(rig.latch.tripped("https://cdn.test/v/seg9.ts"))
        assertTrue(rig.logs.any { it.contains("no gain") })
        rig.open(rig.seg(6))
        assertTrue(rig.executor.tasks.isEmpty())
    }

    @Test
    fun `a fetch that is cancelled while it runs ends as cancelled, not as a failure of the host`() {
        val rig = Rig()
        rig.slowLine()
        rig.fetcher.outcome = { url ->
            if (url.endsWith("seg2.ts")) {
                rig.open(rig.seg(20)) // the player jumps while this one is being fetched
                Outcome.Failed("interrupted") // what a cancelled download ends with
            } else {
                Outcome.Done
            }
        }
        rig.open(rig.seg(1))
        rig.executor.runAll()
        assertFalse(rig.latch.tripped("https://cdn.test/v/seg9.ts"))
        assertTrue(rig.fetcher.fetched.contains(rig.seg(2)))
        assertFalse(rig.fetcher.fetched.contains(rig.seg(3)))
    }

    @Test
    fun `a file that failed on its own is not asked for again while the stream plays`() {
        val rig = Rig()
        rig.fetcher.outcome = { if (it.endsWith("seg2.ts")) Outcome.Failed("HTTP 404", hostWide = false) else Outcome.Done }
        rig.slowLine()
        rig.open(rig.seg(1))
        rig.executor.runAll()
        rig.open(rig.seg(1)) // the window comes round again
        rig.executor.runAll()
        assertEquals(1, rig.fetcher.fetched.count { it.endsWith("seg2.ts") })
    }

    @Test
    fun `an address with an underscore in the host name or odd characters in it is handled`() {
        val rig = Rig()
        val base = "https://cdn_1.test/v/"
        rig.add("${base}index.m3u8", 30)
        rig.read(0, 7_000, base)
        rig.open(rig.seg(1, base))
        rig.executor.runAll()
        assertEquals(listOf(rig.seg(2, base), rig.seg(3, base), rig.seg(4, base)), rig.fetcher.fetched)
        assertEquals("cdn_1.test", FallbackLatch.hostOf("https://cdn_1.test/a|b c.ts"))
    }

    @Test
    fun `a read that shared the line with fetches ahead does not give the speed of one connection`() {
        val rig = Rig()
        rig.slowLine()
        rig.open(rig.seg(1)) // fetches ahead start now, while the player reads 1
        rig.scheduler.onPlayerDone(rig.seg(1), Mode.DIRECT, 350_000, true, 1_000) // looks fast: it was not alone
        assertEquals(0.5, rig.scheduler.speedRatio!!, 1e-9)
    }

    @Test
    fun `a read that began while a fetch ahead was running is not counted either`() {
        val rig = Rig()
        rig.slowLine()
        rig.open(rig.seg(1)) // 2 and 3 in flight
        rig.scheduler.onPlayerDone(rig.seg(1), Mode.DIRECT, 350_000, true, 7_000)
        rig.open(rig.seg(10)) // a jump; the old ones are cancelled but still count as running until they stop
        rig.scheduler.onPlayerDone(rig.seg(10), Mode.DIRECT, 350_000, true, 500)
        assertEquals(0.5, rig.scheduler.speedRatio!!, 1e-9)
    }

    @Test
    fun `one slow batch among good ones does not end the fetching ahead`() {
        val rig = Rig()
        rig.slowLine()
        rig.fetchTakes(21) // slow
        rig.open(rig.seg(1))
        rig.executor.tasks.removeFirst().run() // one slow batch
        rig.fetchTakes(7) // the line is as good as the player's own again: the count of slow ones starts over
        rig.executor.runAll()
        rig.fetchTakes(21)
        rig.open(rig.seg(8))
        rig.executor.tasks.removeFirst().run() // one slow again, but not two in a row
        assertFalse(rig.latch.tripped("https://cdn.test/v/seg9.ts"))
    }

    @Test
    fun `a fetch ahead that is too small to time is not judged`() {
        val rig = Rig()
        rig.slowLine()
        rig.fetchTakes(21, bytes = 40_000) // slow for its size, but 40 KB is mostly the set-up of a connection (3 of them: 120 KB)
        rig.open(rig.seg(1))
        rig.executor.runAll()
        assertFalse(rig.latch.tripped("https://cdn.test/v/seg9.ts"))
        assertEquals(listOf(rig.seg(2), rig.seg(3), rig.seg(4)), rig.fetcher.fetched)
    }

    @Test
    fun `a segment the player opened while its fetch waited for a thread is not fetched at all`() {
        val rig = Rig()
        rig.slowLine()
        rig.open(rig.seg(1)) // 2 and 3 wait for a thread
        assertEquals(Mode.JOINED, rig.open(rig.seg(3))) // the player is already at 3
        rig.executor.runAll()
        assertFalse(rig.fetcher.fetched.contains(rig.seg(3))) // the player has it: a second connection would only take from it
        assertFalse(rig.fetcher.fetched.contains(rig.seg(2))) // behind the player now: cancelled
        assertTrue(rig.fetcher.fetched.contains(rig.seg(4))) // ahead of it: fetched
    }

    @Test
    fun `small segments are added up until there is enough to judge by`() {
        val rig = Rig()
        rig.slowLine()
        rig.fetchTakes(21, bytes = 65_000) // each too small to time; two together (130 KB) enough, and a third of the speed
        rig.open(rig.seg(1))
        rig.executor.runAll() // 2 and 3: the first batch; 4 waits in the next one
        assertFalse(rig.latch.tripped("https://cdn.test/v/seg9.ts"))
        rig.open(rig.seg(6))
        rig.executor.runAll() // 7 completes the second batch (with 4): two in a row
        assertTrue(rig.latch.tripped("https://cdn.test/v/seg9.ts"))
        assertTrue(rig.logs.any { it.contains("no gain") })
    }

    @Test
    fun `a batch of fetches is judged on its own, not on the whole history`() {
        val rig = Rig()
        rig.slowLine() // the player alone: 50 KB/s
        // First batch: as fast as the player alone (350 KB in 7 s each). Judged, kept, forgotten.
        rig.fetchTakes(7)
        rig.open(rig.seg(1))
        rig.executor.runAll()
        assertFalse(rig.latch.tripped("https://cdn.test/v/seg9.ts"))
        // Later the line is shared: 350 KB takes 21 s. If the first batch still counted, the two would average out and the
        // judgement would be late; as its own batch the slow fetch is judged at once.
        rig.fetchTakes(21)
        rig.open(rig.seg(6))
        rig.executor.tasks.removeFirst().run() // one slow batch is not yet the line
        assertFalse(rig.latch.tripped("https://cdn.test/v/seg9.ts"))
        rig.executor.tasks.removeFirst().run() // two in a row are
        assertTrue(rig.latch.tripped("https://cdn.test/v/seg9.ts"))
    }

    @Test
    fun `two slow fetches that ran side by side are one observation of the line, not two`() {
        val rig = Rig()
        rig.slowLine()
        rig.fetchTakes(21)
        rig.open(rig.seg(1)) // 2 and 3 start together, 4 waits
        rig.nowNs = 0
        rig.executor.tasks.removeFirst().run() // 2: from 0 to 21 s
        rig.nowNs = 0
        rig.executor.tasks.removeFirst().run() // 3 ran alongside it: also from 0 to 21 s, hit by the same hiccup
        assertFalse(rig.latch.tripped("https://cdn.test/v/seg9.ts"))
        rig.executor.tasks.removeFirst().run() // 4 began after they ended, and is slow too: a second observation
        assertTrue(rig.latch.tripped("https://cdn.test/v/seg9.ts"))
    }

    @Test
    fun `starting over forgets the slow batch that was counted`() {
        val rig = Rig()
        rig.slowLine()
        rig.fetchTakes(21)
        rig.open(rig.seg(1))
        rig.executor.tasks.removeFirst().run() // one slow batch counted
        rig.scheduler.reset() // another line, another episode
        rig.executor.runAll() // what was in flight was cancelled
        rig.slowLine()
        rig.open(rig.seg(10)) // segments not fetched before: a cached one makes no sample
        rig.executor.tasks.removeFirst().run() // one slow batch again: the first is forgotten, so this is the first
        assertTrue(rig.fetcher.fetched.contains(rig.seg(11))) // it was fetched, and judged
        assertFalse(rig.latch.tripped("https://cdn.test/v/seg9.ts"))
    }

    @Test
    fun `fetches that overlap in a pipeline still give a second observation`() {
        val rig = Rig()
        rig.slowLine()
        rig.fetchTakes(21)
        rig.open(rig.seg(1)) // 2 and 3 start together, 4 follows when 2 is done
        rig.nowNs = 0
        rig.executor.tasks.removeFirst().run() // 2: from 0 to 21 s (the first low batch)
        rig.nowNs = 6_000_000_000
        rig.executor.tasks.removeFirst().run() // 3: from 6 to 27 s, alongside 2 and 4
        rig.nowNs = 21_000_000_000
        rig.executor.tasks.removeFirst().run() // 4: from 21 to 42 s, begun the moment 2 ended: an observation of its own
        assertTrue(rig.latch.tripped("https://cdn.test/v/seg9.ts"))
    }
}
