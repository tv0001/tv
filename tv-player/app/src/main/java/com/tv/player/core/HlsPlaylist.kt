package com.tv.player.core

/**
 * What fetching ahead needs from an HLS playlist: the playlists a master points to, or the segments of a media playlist
 * in order. [resolve] turns a reference into an address the way the player does, so a segment is fetched under exactly
 * the address (and cache key) the player will later ask for.
 */
object HlsPlaylist {
    class Segment(val url: String, val seconds: Double)

    sealed class Parsed {
        class Master(val variants: List<String>) : Parsed()
        class Media(val segments: List<Segment>) : Parsed()

        /** Not a playlist, no segments, or segments that are parts of a file (byte ranges): left to the player alone. */
        object Unusable : Parsed()
    }

    fun parse(text: String, base: String, resolve: (base: String, reference: String) -> String): Parsed = try {
        val lines = text.removePrefix("\uFEFF").lines().map { it.trim() }
        when {
            lines.none { it.startsWith("#EXTM3U") } -> Parsed.Unusable
            lines.any { it.startsWith("#EXT-X-STREAM-INF") } -> {
                val variants = lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF") }.mapNotNull { i ->
                    lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                }.map { resolve(base, it) }
                if (variants.isEmpty()) Parsed.Unusable else Parsed.Master(variants)
            }
            lines.any { it.startsWith("#EXT-X-BYTERANGE") } -> Parsed.Unusable
            else -> {
                val segments = ArrayList<Segment>()
                var seconds = 0.0
                for (line in lines) {
                    if (line.startsWith("#EXTINF:")) {
                        seconds = line.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() ?: 0.0
                    } else if (line.isNotEmpty() && !line.startsWith("#")) {
                        segments += Segment(resolve(base, line), seconds)
                        seconds = 0.0
                    }
                }
                if (segments.isEmpty()) Parsed.Unusable else Parsed.Media(segments)
            }
        }
    } catch (e: Exception) {
        Parsed.Unusable
    }
}
