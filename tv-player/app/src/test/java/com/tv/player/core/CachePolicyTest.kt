package com.tv.player.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CachePolicyTest {
    @Test
    fun `media segments and files are cached`() {
        assertTrue(CachePolicy.cacheable("https", "/20250415/469108/3000k/hls/3b41ae94.ts"))
        assertTrue(CachePolicy.cacheable("http", "/video/ep01.mp4"))
        assertTrue(CachePolicy.cacheable("HTTPS", "/seg/init.m4s"))
    }

    @Test
    fun `playlists and keys are always read from the network`() {
        assertFalse(CachePolicy.cacheable("https", "/20250415/469108/index.m3u8"))
        assertFalse(CachePolicy.cacheable("https", "/a/B.M3U8"))
        assertFalse(CachePolicy.cacheable("https", "/a/enc.key"))
        assertFalse(CachePolicy.cacheable("https", "/a/sub.vtt"))
    }

    @Test
    fun `what is not on the web stays out`() {
        assertFalse(CachePolicy.cacheable("file", "/data/user/0/com.tv.player/files/sub.srt"))
        assertFalse(CachePolicy.cacheable("content", "/media/1"))
        assertFalse(CachePolicy.cacheable(null, "/x.ts"))
    }
}
