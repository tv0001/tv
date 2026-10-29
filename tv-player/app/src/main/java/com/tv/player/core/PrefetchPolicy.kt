package com.tv.player.core

import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** The numbers and rules behind reading one source over several connections at once. */
object PrefetchPolicy {
    /** How many segments after the one being played are fetched ahead. */
    const val WINDOW = 3

    /** Connections used for fetching ahead, besides the player's own. */
    const val MAX_CONCURRENT = 2

    /**
     * Fetching ahead starts only when the player's own download of a segment runs slower than this many times real time:
     * a line that is faster than that needs nothing, and a line that is not limited per connection gains little.
     */
    const val SLOW_RATIO = 2.0

    /** A file is read in pieces this big, [PARALLEL] of them at once. */
    const val CHUNK_BYTES = 512 * 1024
    const val PARALLEL = 3

    /**
     * Reading a file in pieces is checked for gain: the first piece is timed alone (after [SKIP_BYTES], which may already
     * have been waiting in a buffer, for [SOLO_BYTES]), then while the other pieces download ([CHECK_BYTES]). A line that
     * shares its speed between connections gives the first piece less than [MIN_GAIN] of what it had alone: more
     * connections gain nothing there, and only slow the piece the player is waiting for. The same bar is applied to
     * segments fetched ahead against what the player's own connection got.
     */
    const val SKIP_BYTES = 16 * 1024
    const val SOLO_BYTES = 48 * 1024
    const val CHECK_BYTES = 64 * 1024
    const val MIN_GAIN = 0.6

    /**
     * The time alone is timed in two halves, and they must agree: the slower must be at least this much of the faster.
     * Otherwise the window straddles a change of speed (bytes that were waiting in a buffer, then the line's own speed)
     * and says nothing about the line; it is timed again from where it stands.
     */
    const val STEADY_RATIO = 0.7

    /** The first piece's time alone must have lasted at least this long to be compared with: a burst out of a buffer proves nothing. */
    const val MIN_SOLO_MS = 30L

    /**
     * The first piece arriving at this speed (8 Mbps) means one connection is enough for this open. The rate of the file
     * is not known here, so the bar is above what a film on these sites needs; a file that needs more than a line gives
     * one connection is not told apart from that, and plays as it did before.
     */
    const val FAST_BYTES_PER_SEC = 1_000_000L

    /** Fetches ahead smaller than this are not timed against the player's own connection: the set-up of a connection is most of them. */
    const val MIN_JUDGE_BYTES = 128L * 1024

    private val files = Regex("\\.(mp4|m4v|mov|mkv|webm)$", RegexOption.IGNORE_CASE)

    /** Whole video files are read in pieces; segments (.ts, .m4s) are fetched ahead one by one instead. */
    fun rangedFile(path: String?) = files.containsMatchIn(path.orEmpty())

    private val contentRange = Regex("bytes\\s+(\\d+)-\\d+/(\\d+)", RegexOption.IGNORE_CASE)

    /** The file size out of "Content-Range: bytes 0-524287/14447354"; null when the answer is not a range of a known size. */
    fun contentRangeTotal(headers: Map<String, List<String>>): Long? {
        val value = headers.entries.firstOrNull { it.key.equals("Content-Range", true) }?.value?.firstOrNull() ?: return null
        return contentRange.find(value)?.groupValues?.get(2)?.toLongOrNull()
    }

    /** Where the answer to a Range request starts ("bytes 524288-1048575/14447354" -> 524288); null when it says nothing. */
    fun contentRangeStart(headers: Map<String, List<String>>): Long? {
        val value = headers.entries.firstOrNull { it.key.equals("Content-Range", true) }?.value?.firstOrNull() ?: return null
        return contentRange.find(value)?.groupValues?.get(1)?.toLongOrNull()
    }
}

/**
 * Where parallel reading has been given up for this playback: a host that answered 403 / 429 / refused / timed out is
 * not tried again; a file that is served whole whatever is asked, that is gone, or that one connection already
 * downloads fast, is left alone (that one file, not its host).
 */
class FallbackLatch {
    private val hosts: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val files: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun trip(url: String) {
        hostOf(url)?.let { hosts += it }
    }

    /** True for an address that cannot be read (no host): nothing is fetched for those. */
    fun tripped(url: String): Boolean = hostOf(url)?.let { it in hosts } ?: true

    fun skip(url: String) {
        files += url
    }

    fun skipped(url: String): Boolean = url in files

    companion object {
        // java.net.URL, not URI: URI has no host for names with an underscore and refuses a space or a '|' in an address.
        fun hostOf(url: String): String? = runCatching { URL(url).host?.lowercase()?.takeIf { it.isNotEmpty() } }.getOrNull()
    }
}
