package com.tv.player.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.net.URL

/**
 * Tests one playback line before it is played: downloads a real video segment and compares the speed with what the
 * stream needs, and works out the resolution it would play at. A line is "smooth" when it downloads at least
 * 1.5x faster than it plays.
 */
object LineProbe {
    private const val SMOOTH_RATIO = 1.5
    private const val FALLBACK_NEED_MBPS = 2.5

    class Result(
        val ok: Boolean,
        val stream: String? = null,
        val height: Int = 0,
        val mbps: Double = 0.0,
        val needMbps: Double = 0.0,
        val smooth: Boolean = false,
    ) {
        override fun toString() = "ok=$ok height=$height speed=%.1fMbps need=%.1fMbps smooth=$smooth".format(java.util.Locale.ROOT, mbps, needMbps)
    }

    private class Variant(val url: String, val bandwidth: Long, val height: Int)

    private val failed = Result(false)

    /** [capHeight] is the screen's short side: a variant taller than the panel is never counted as an advantage. */
    suspend fun probe(playUrl: String, capHeight: Int): Result = try {
        withTimeout(7_000) { run(playUrl, capHeight) }
    } catch (e: TimeoutCancellationException) {
        failed
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failed
    }

    private suspend fun run(playUrl: String, capHeight: Int): Result {
        val stream = Resolve.stream(playUrl) ?: return failed
        if (!stream.contains(".m3u8", ignoreCase = true)) {
            val f = Http.fetchBytes(stream, 4_000, 1_500_000, maxReadMs = 1_500)
            if (f.status !in 200..299 || f.bytes <= 0) return failed
            val mbps = throughput(f)
            return Result(true, stream, 0, mbps, FALLBACK_NEED_MBPS, mbps >= FALLBACK_NEED_MBPS * SMOOTH_RATIO && f.ttfbMs <= 3_000)
        }

        var url = stream
        var text = playlist(url) ?: return failed
        var variants = parseVariants(text, url)
        var chosen: Variant? = null
        var capped: List<Variant> = emptyList()
        if (variants.isNotEmpty()) {
            variants = variants.sortedBy { it.bandwidth }
            capped = variants.filter { it.height == 0 || it.height <= capHeight }.ifEmpty { listOf(variants.first()) }
            val middle = capped[capped.size / 2] // a middle variant is enough to measure the line's throughput
            url = middle.url
            text = playlist(url) ?: return failed
            chosen = middle
        }

        val (segment, duration) = firstSegment(text, url) ?: return failed
        // Sample for a fixed 1.5 s instead of downloading a whole segment: a slow line must read as slow, not as dead.
        val f = Http.fetchBytes(segment, 4_000, 1_500_000, maxReadMs = 1_500)
        if (f.status !in 200..299 || f.bytes <= 0) return failed
        val mbps = throughput(f)

        if (chosen != null) {
            // ExoPlayer adapts between variants on its own; the best one this connection could sustain decides the rank.
            val sustainable = capped.lastOrNull { it.bandwidth / 1e6 * SMOOTH_RATIO <= mbps } ?: capped.first()
            val need = sustainable.bandwidth / 1e6
            val height = if (sustainable.height > 0) sustainable.height else pseudoHeight(need)
            return Result(true, stream, height, mbps, need, mbps >= need * SMOOTH_RATIO && f.ttfbMs <= 3_000)
        }
        val need = if (f.total > 0 && duration > 0) f.total * 8.0 / 1e6 / duration else FALLBACK_NEED_MBPS
        return Result(true, stream, pseudoHeight(need), mbps, need, mbps >= need * SMOOTH_RATIO && f.ttfbMs <= 3_000)
    }

    /** Megabits per second over the read phase (connection set-up time is judged separately). */
    private fun throughput(f: Http.Fetched) = f.bytes * 8.0 / 1000.0 / maxOf(f.readMs, 20L)

    /** Plain media playlists carry no resolution, so the bitrate stands in for it when ranking. */
    private fun pseudoHeight(mbps: Double) = when {
        mbps >= 8 -> 2160
        mbps >= 3.5 -> 1080
        mbps >= 1.8 -> 720
        mbps >= 0.9 -> 480
        else -> 360
    }

    private suspend fun playlist(url: String): String? {
        val r = Http.get(url, timeoutMs = 4_000, limitBytes = 300_000)
        return r.text.takeIf { r.status == 200 && it.contains("#EXTM3U") }
    }

    private fun resolve(base: String, ref: String) = runCatching { URL(URL(base), ref).toString() }.getOrNull()

    private fun parseVariants(text: String, base: String): List<Variant> {
        val out = ArrayList<Variant>()
        val lines = text.lines().map { it.trim() }
        for (i in lines.indices) {
            val l = lines[i]
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue
            val bandwidth = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val height = Regex("RESOLUTION=\\d+x(\\d+)").find(l)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            resolve(base, uri)?.let { out += Variant(it, bandwidth, height) }
        }
        return out
    }

    private fun firstSegment(text: String, base: String): Pair<String, Double>? {
        val lines = text.lines().map { it.trim() }
        for (i in lines.indices) {
            val l = lines[i]
            if (!l.startsWith("#EXTINF:")) continue
            val duration = l.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() ?: 0.0
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            return resolve(base, uri)?.let { it to duration }
        }
        return null
    }
}
