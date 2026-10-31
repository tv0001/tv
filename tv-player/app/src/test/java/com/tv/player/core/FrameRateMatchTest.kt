package com.tv.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateMatchTest {
    private val hz60 = FrameRateMatch.Mode(1, 1920, 1080, 60f)
    private val tv = listOf(
        hz60,
        FrameRateMatch.Mode(2, 1920, 1080, 50f),
        FrameRateMatch.Mode(3, 1920, 1080, 24f),
        FrameRateMatch.Mode(4, 1920, 1080, 23.976f),
        FrameRateMatch.Mode(5, 1920, 1080, 59.94f),
        FrameRateMatch.Mode(6, 3840, 2160, 24f),
    )

    @Test
    fun `a film goes to the matching rate at the same resolution`() {
        assertEquals(3, FrameRateMatch.pick(24f, hz60, tv))
        assertEquals(4, FrameRateMatch.pick(23.976f, hz60, tv))
        assertEquals(2, FrameRateMatch.pick(25f, hz60, tv))
        assertEquals(5, FrameRateMatch.pick(29.97f, hz60, tv))
    }

    @Test
    fun `nothing changes when the current rate already fits or nothing fits`() {
        assertNull(FrameRateMatch.pick(30f, hz60, tv)) // 60 是 30 的两倍
        assertNull(FrameRateMatch.pick(60f, hz60, tv))
        assertNull(FrameRateMatch.pick(24f, hz60, listOf(hz60))) // 电视只有 60Hz
        assertNull(FrameRateMatch.pick(-1f, hz60, tv)) // 帧率未知
    }

    @Test
    fun `23_976 and 24 are told apart`() {
        assertTrue(FrameRateMatch.fits(24f, 24f))
        assertFalse(FrameRateMatch.fits(24f, 23.976f))
        assertTrue(FrameRateMatch.fits(47.952f, 23.976f))
        assertFalse(FrameRateMatch.fits(60f, 59.94f))
    }

    @Test
    fun `the rate is estimated from frame times despite a seek`() {
        val e = FrameRateMatch.Estimator(frames = 30)
        var t = 0L
        var got: Float? = null
        for (i in 0 until 40) {
            t += if (i == 10) 5_000_000L else 41_708L // 23.976 帧，中间 seek 跳了 5 秒
            got = e.add(t) ?: got
        }
        assertEquals(23.976f, got!!, 0.001f)
        assertNull(e.add(t + 41_708L)) // 估出一次之后不再报
        e.reset()
        assertNull(e.add(0))
    }

    @Test
    fun `an unusual rate is kept as it is`() {
        assertEquals(12.5f, FrameRateMatch.snap(12.5f), 0.001f)
        assertEquals(25f, FrameRateMatch.snap(25.03f), 0.001f)
    }
}
