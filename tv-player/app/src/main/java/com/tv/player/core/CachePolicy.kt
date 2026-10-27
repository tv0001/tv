package com.tv.player.core

/**
 * What goes into the playback disk cache. Only the media itself: the playlist (.m3u8) and the key file are read from the
 * network every time, so a bad answer once (a login page, an error page) or a playlist whose segment addresses have since
 * expired is never served again from the disk. Anything that is not http(s) (a subtitle file on the phone) stays out too.
 */
object CachePolicy {
    private val neverCached = Regex("\\.(m3u8?|key|vtt|srt|ass)$", RegexOption.IGNORE_CASE)

    fun cacheable(scheme: String?, path: String?): Boolean {
        if (!scheme.equals("http", true) && !scheme.equals("https", true)) return false
        return !neverCached.containsMatchIn(path.orEmpty())
    }
}
