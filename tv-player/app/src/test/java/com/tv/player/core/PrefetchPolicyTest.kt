package com.tv.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefetchPolicyTest {
    @Test
    fun `video files are read in pieces, segments are not`() {
        assertTrue(PrefetchPolicy.rangedFile("/video/ep01.mp4"))
        assertTrue(PrefetchPolicy.rangedFile("/a/B.MKV"))
        assertTrue(PrefetchPolicy.rangedFile("/a/b.webm"))
        assertEquals(false, PrefetchPolicy.rangedFile("/a/seg1.ts"))
        assertEquals(false, PrefetchPolicy.rangedFile("/a/index.m3u8"))
        assertEquals(false, PrefetchPolicy.rangedFile("/a/mp4/play"))
        assertEquals(false, PrefetchPolicy.rangedFile(null))
    }

    @Test
    fun `the latch is per host, and an unreadable address counts as tripped`() {
        val latch = FallbackLatch()
        assertEquals(false, latch.tripped("https://a.test/x.ts"))
        latch.trip("https://A.test/y.ts")
        assertTrue(latch.tripped("https://a.test/x.ts"))
        assertEquals(false, latch.tripped("https://b.test/x.ts"))
        assertTrue(latch.tripped("not an address"))
    }

    @Test
    fun `a file that one connection reads fast is skipped by address`() {
        val latch = FallbackLatch()
        latch.skip("https://a.test/m.mp4")
        assertTrue(latch.skipped("https://a.test/m.mp4"))
        assertEquals(false, latch.skipped("https://a.test/n.mp4"))
        assertEquals(false, latch.tripped("https://a.test/n.mp4"))
    }

    @Test
    fun `the file size comes out of the Content-Range answer`() {
        assertEquals(14447354L, PrefetchPolicy.contentRangeTotal(mapOf("Content-Range" to listOf("bytes 0-524287/14447354"))))
        assertEquals(100L, PrefetchPolicy.contentRangeTotal(mapOf("content-range" to listOf("bytes 50-99/100"))))
        assertEquals(null, PrefetchPolicy.contentRangeTotal(mapOf("Content-Range" to listOf("bytes 0-99/*"))))
        assertEquals(null, PrefetchPolicy.contentRangeTotal(mapOf("Content-Length" to listOf("100"))))
        assertEquals(null, PrefetchPolicy.contentRangeTotal(emptyMap()))
    }

    @Test
    fun `where an answer to a Range request begins`() {
        assertEquals(524288L, PrefetchPolicy.contentRangeStart(mapOf("Content-Range" to listOf("bytes 524288-1048575/14447354"))))
        assertEquals(0L, PrefetchPolicy.contentRangeStart(mapOf("content-range" to listOf("bytes 0-99/100"))))
        assertEquals(null, PrefetchPolicy.contentRangeStart(mapOf("Content-Range" to listOf("bytes */100"))))
        assertEquals(null, PrefetchPolicy.contentRangeStart(emptyMap()))
    }

    @Test
    fun `the unit of a Content-Range is not case sensitive`() {
        assertEquals(100L, PrefetchPolicy.contentRangeTotal(mapOf("Content-Range" to listOf("Bytes 0-1/100"))))
        assertEquals(0L, PrefetchPolicy.contentRangeStart(mapOf("Content-Range" to listOf("BYTES 0-1/100"))))
    }
}
