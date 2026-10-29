package com.tv.player.core

import java.net.URL
import java.util.concurrent.Executor

/**
 * Fetches the next few segments of an HLS stream into the disk cache while the player is still on the current one, for
 * lines that limit each connection: the player reads segment N on its own connection, and N+1..N+3 come in over others.
 *
 * Rules, all decided here so they can be tested without a player:
 *  - nothing is fetched until the player's own downloads have shown a slow line ([PrefetchPolicy.SLOW_RATIO]);
 *  - nothing is started before the player's own request is open (a server that lets one client have one connection at a
 *    time must refuse the extra ones, not the player's);
 *  - at most [maxConcurrent] fetches at once, never the segment the player is already reading, nothing outside the window;
 *  - when the player jumps (a seek) or moves on, fetches no longer ahead of it are cancelled and dropped;
 *  - the first failure that points at the host (403, 429, refused, timed out) stops fetching ahead from that host for good,
 *    and so does finding that the extra connections gain nothing (what they fetch comes in at well under the speed the
 *    player's own connection had: the line shares one speed between them, or serves them all over one connection);
 *  - only what [CachePolicy] allows, and only for the source being played ([allowed]), never for what is loaded ahead of it.
 */
class PrefetchScheduler(
    private val fetcher: Fetcher,
    private val executor: Executor,
    private val latch: FallbackLatch,
    /** Whether fetching ahead is on for the stream with this address (the setting, the network, and that it is the one playing). */
    private val allowed: (root: String) -> Boolean,
    private val exclude: (url: String) -> Boolean = { false },
    private val log: (String) -> Unit = {},
    private val window: Int = PrefetchPolicy.WINDOW,
    private val maxConcurrent: Int = PrefetchPolicy.MAX_CONCURRENT,
    private val clock: () -> Long = System::nanoTime,
) {
    interface Fetcher {
        /** Whether the whole of [url] is already in the cache. */
        fun isCached(url: String): Boolean

        /** Whether any of [url] is in the cache (a fetch that was cancelled half way leaves a part). */
        fun hasAny(url: String): Boolean

        /** Downloads [url] into the cache; returns when it is there, or stopped early. Must stop soon after [CancelToken.cancel]. */
        fun fetch(url: String, token: CancelToken): Outcome
    }

    sealed interface Outcome {
        object Done : Outcome

        /** Done, and it was [bytes] that were downloaded: what its speed is judged by. */
        class Fetched(val bytes: Long) : Outcome
        object Cancelled : Outcome

        /** [hostWide]: the failure says something about the host (403, 429, refused...) and not just about this file (404, a full disk). */
        class Failed(val reason: String, val hostWide: Boolean = true) : Outcome
    }

    class CancelToken {
        @Volatile
        var cancelled = false
            private set
        private var hook: (() -> Unit)? = null

        @Synchronized
        fun cancel() {
            if (cancelled) return
            cancelled = true
            hook?.invoke()
        }

        /** [hook] runs on cancel, at once if that already happened. */
        @Synchronized
        fun onCancel(hook: () -> Unit) {
            this.hook = hook
            if (cancelled) hook()
        }
    }

    /** How the player's read of a segment relates to fetching ahead. */
    enum class Mode {
        /** Some or all of it was already in the cache: its time says nothing about the line. */
        HIT,

        /** It was being fetched ahead; the player waits for that one. */
        JOINED,

        /** Only the player is downloading it. */
        DIRECT,
    }

    private class Loc(val root: String, val index: Int, val list: List<HlsPlaylist.Segment>)

    private val lock = Any()
    private val playlists = object : LinkedHashMap<String, List<HlsPlaylist.Segment>>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<HlsPlaylist.Segment>>): Boolean {
            if (size <= MAX_PLAYLISTS) return false
            // Only what still belongs to the playlist going away (a later one may list the same address).
            eldest.value.forEach { s -> if (locs[s.url]?.list === eldest.value) locs.remove(s.url) }
            return true
        }
    }
    private val locs = HashMap<String, Loc>()
    private val rootOf = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > MAX_PLAYLISTS * 4
    }
    private val queue = ArrayDeque<String>()
    private val running = HashMap<String, CancelToken>()
    private val playerBusy = HashSet<String>()

    // Segments the player read (or is reading) while fetches ahead were running: they shared the line, so their speed is
    // not that of one connection and must not be taken for it.
    private val contended = HashSet<String>()
    private val failedFiles = HashSet<String>()
    private var activeRoot: String? = null
    private var ratio: Double? = null

    // Bytes per second of the player's own downloads, and of what was fetched ahead (smoothed).
    private var directBps: Double? = null
    private var aheadBytes = 0L
    private var aheadMs = 0L
    private var batchStartedNs = 0L
    private var lowBatches = 0
    private var lastLowEndedNs = 0L
    private var closed = false

    /** Real time per second of download, as the player's own downloads showed it (null: not known yet). */
    val speedRatio: Double? get() = synchronized(lock) { ratio }

    /**
     * A playlist the player has read completely. [url] is the address it asked for, [base] the one the answer came from
     * (after redirects): relative references are resolved against that, as the player does.
     */
    fun onPlaylist(url: String, base: String, text: String, resolve: (String, String) -> String) {
        when (val parsed = HlsPlaylist.parse(text, base, resolve)) {
            is HlsPlaylist.Parsed.Master -> synchronized(lock) { parsed.variants.forEach { rootOf[it] = url } }
            is HlsPlaylist.Parsed.Media -> synchronized(lock) {
                playlists.remove(url)?.let { old -> old.forEach { s -> if (locs[s.url]?.list === old) locs.remove(s.url) } }
                val root = rootOf[url] ?: url
                playlists[url] = parsed.segments
                parsed.segments.forEachIndexed { i, s -> locs[s.url] = Loc(root, i, parsed.segments) }
            }
            HlsPlaylist.Parsed.Unusable -> Unit
        }
    }

    /**
     * The player is about to open [url]. Null when it is not a segment of a playlist seen before. What is no longer ahead of
     * the player (it jumped) is cancelled now, so those connections are closing while its own request goes out. Nothing new
     * is started yet: see [onPlayerOpened].
     */
    fun onPlayerOpen(url: String): Mode? {
        val root = synchronized(lock) { locs[url]?.root } ?: return null
        val on = allowed(root)
        synchronized(lock) {
            // One step, cache look-up included: a fetch cannot finish or start between telling the mode and marking the
            // segment as the player's.
            val mode = when {
                url in running -> Mode.JOINED
                fetcher.hasAny(url) -> Mode.HIT
                else -> Mode.DIRECT
            }
            playerBusy += url
            if (running.isNotEmpty()) contended += url
            if (on) prune(url)
            return mode
        }
    }

    /**
     * The player's own request for [url] is open (the server has answered). Only now are the segments after it fetched
     * ahead: a server that lets one client have one connection at a time must refuse the extra ones, not the player's.
     */
    fun onPlayerOpened(url: String) {
        val root = synchronized(lock) { locs[url]?.root } ?: return
        val on = allowed(root)
        synchronized(lock) { if (on) fill(url) }
    }

    /**
     * The player is done with [url] ([complete]: it read all of it, from the first byte). A download of its own, timed,
     * tells how fast the line is.
     */
    fun onPlayerDone(url: String, mode: Mode, bytes: Long, complete: Boolean, elapsedMs: Long) {
        val root = synchronized(lock) { locs[url]?.root }
        val on = root != null && allowed(root)
        synchronized(lock) {
            playerBusy -= url
            val shared = contended.remove(url)
            val loc = locs[url] ?: return
            if (!on || mode != Mode.DIRECT || shared || !complete || bytes <= 0 || elapsedMs <= 0) return
            val seconds = loc.list[loc.index].seconds
            if (seconds <= 0) return
            val now = seconds * 1000.0 / elapsedMs
            ratio = ratio?.let { it * 0.5 + now * 0.5 } ?: now
            val bps = bytes * 1000.0 / elapsedMs
            directBps = directBps?.let { it * 0.5 + bps * 0.5 } ?: bps
            log("line speed: ${"%.2f".format(java.util.Locale.ROOT, now)}x real time (average ${"%.2f".format(java.util.Locale.ROOT, ratio)}x)")
        }
    }

    /** What the player plays has changed (another episode, another line, stopped): what was fetched ahead goes at once. */
    fun reset() = synchronized(lock) {
        queue.clear()
        running.values.forEach { it.cancel() }
        activeRoot = null
        contended.clear()
        forgetSpeed()
    }

    /** Stops everything and ignores what comes after (the player is released). */
    fun release() = synchronized(lock) {
        closed = true
        queue.clear()
        running.values.forEach { it.cancel() }
    }

    // Called with the lock held. Drops what is no longer ahead of the player at [url].
    private fun prune(url: String) {
        if (closed) return
        val loc = locs[url] ?: return
        if (activeRoot != loc.root) {
            // A different stream is playing now (another episode, another line): what was fetched ahead for the old one goes.
            running.forEach { if (it.key != url) it.value.cancel() }
            queue.clear()
            forgetSpeed()
            activeRoot = loc.root
        }
        val want = ahead(loc)
        // Not ahead of the player any more (it moved on, or jumped): dropped. The one it is on is left to finish.
        queue.removeAll { it !in want }
        running.forEach { if (it.key != url && it.key !in want) it.value.cancel() }
    }

    // Called with the lock held. Queues and starts what is missing ahead of the player at [url].
    private fun fill(url: String) {
        if (closed) return
        val loc = locs[url] ?: return
        if (activeRoot != loc.root) return
        if (ratio?.let { it < PrefetchPolicy.SLOW_RATIO } != true) return
        for (u in ahead(loc)) {
            if (u in running || u in queue || u in playerBusy || u in failedFiles || exclude(u) || latch.tripped(u) || !cacheable(u)) continue
            queue.addLast(u)
        }
        pump()
    }

    // Lock held.
    private fun forgetSpeed() {
        ratio = null
        directBps = null
        aheadBytes = 0
        aheadMs = 0
        batchStartedNs = 0
        lowBatches = 0
        lastLowEndedNs = 0
        failedFiles.clear()
    }

    private fun ahead(loc: Loc) = (1..window).mapNotNull { loc.list.getOrNull(loc.index + it)?.url }

    // Called with the lock held.
    private fun pump() {
        while (!closed && running.size < maxConcurrent && queue.isNotEmpty()) {
            val url = queue.removeFirst()
            val token = CancelToken()
            running[url] = token
            contended += playerBusy
            try {
                executor.execute { run(url, token) }
            } catch (e: Exception) {
                running.remove(url)
            }
        }
    }

    private fun run(url: String, token: CancelToken) {
        // The player may have opened this very segment while it waited for a thread: it downloads it itself, and a second
        // connection on the same bytes would only take from it.
        val taken = synchronized(lock) { url in playerBusy }
        val t0 = clock()
        val outcome = when {
            token.cancelled || taken -> Outcome.Cancelled
            else -> try {
                if (fetcher.isCached(url)) Outcome.Done else fetcher.fetch(url, token)
            } catch (e: Throwable) {
                Outcome.Failed(e.javaClass.simpleName + ": " + e.message)
            }
        }
        val t1 = clock()
        val elapsedMs = (t1 - t0) / 1_000_000
        synchronized(lock) {
            running.remove(url)
            if (outcome is Outcome.Fetched && !token.cancelled) judgeGain(url, outcome.bytes, elapsedMs, t0, t1)
            // Cancelled on purpose (a seek): whatever the fetch ended with is no failure of the host.
            if (outcome is Outcome.Failed && !token.cancelled) {
                if (outcome.hostWide) {
                    log("fetch ahead failed, back to one connection for ${FallbackLatch.hostOf(url)}: ${outcome.reason}")
                    latch.trip(url)
                    val host = FallbackLatch.hostOf(url)
                    queue.removeAll { FallbackLatch.hostOf(it) == host }
                    running.forEach { if (FallbackLatch.hostOf(it.key) == host) it.value.cancel() }
                } else {
                    // Not asked for again while this stream plays: it would only fail again, or be downloaded for nothing.
                    failedFiles += url
                    log("fetch ahead skipped for one file: ${outcome.reason}")
                }
            }
            pump()
        }
    }

    // Lock held: a fetch ahead of [bytes] took [elapsedMs] (from [startedNs] to [endedNs]). Slower than the player's own
    // connection by far: no gain. Small segments are added up until there is enough to time: the set-up of a connection is
    // most of a small one.
    private fun judgeGain(url: String, bytes: Long, elapsedMs: Long, startedNs: Long, endedNs: Long) {
        val direct = directBps ?: return
        if (bytes <= 0 || elapsedMs <= 0) return
        if (aheadBytes == 0L) batchStartedNs = startedNs
        aheadBytes += bytes
        aheadMs += elapsedMs
        if (aheadBytes < PrefetchPolicy.MIN_JUDGE_BYTES) return
        val average = aheadBytes * 1000.0 / aheadMs // per connection: the time of each, not of all together
        val startedAt = batchStartedNs
        // A batch is judged once and then forgotten: the line of an hour ago says little about the line now.
        aheadBytes = 0
        aheadMs = 0
        if (average < PrefetchPolicy.MIN_GAIN * direct) {
            // One slow batch can be anything (other traffic, a hiccup of the server). Two are the line, if the second began
            // after the first had ended: fetches that ran side by side, hit by the same hiccup, are one observation.
            if (lowBatches == 0 || startedAt >= lastLowEndedNs) {
                lowBatches++
                lastLowEndedNs = endedNs // only a batch that counted moves the mark: pieces of a pipeline overlap one another
            }
        } else {
            lowBatches = 0
        }
        if (lowBatches >= 2) {
            log("no gain from extra connections (${(average / 1000).toLong()} KB/s each against ${(direct / 1000).toLong()} KB/s alone), back to one connection for ${FallbackLatch.hostOf(url)}")
            latch.trip(url)
            val host = FallbackLatch.hostOf(url)
            queue.removeAll { FallbackLatch.hostOf(it) == host }
            running.forEach { if (FallbackLatch.hostOf(it.key) == host) it.value.cancel() }
        }
    }

    // java.net.URL, not URI: URI refuses a space or a '|' in an address and has no host for a name with an underscore.
    private fun cacheable(url: String): Boolean = runCatching {
        val u = URL(url)
        CachePolicy.cacheable(u.protocol, u.path)
    }.getOrDefault(false)

    private companion object {
        /** Playlists remembered (one stream has one per picture size; episodes come and go). */
        const val MAX_PLAYLISTS = 12
    }
}
