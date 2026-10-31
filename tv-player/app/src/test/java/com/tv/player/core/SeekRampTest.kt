package com.tv.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekRampTest {
    private val film = 2 * 3_600_000L
    private val episode = 20 * 60_000L

    @Test
    fun `the longer the key is held the faster it moves`() {
        val start = SeekRamp.rate(500, film)
        val middle = SeekRamp.rate(2_000, film)
        val late = SeekRamp.rate(6_000, film)
        assertTrue(start < middle && middle < late)
        assertEquals(20L, start)
        assertEquals(180L, late)
    }

    @Test
    fun `a short episode is never crossed too fast`() {
        // 20 分钟的一集：每秒最多走 1 分钟（全长的 1/20）
        assertEquals(60L, SeekRamp.rate(6_000, episode))
        // 很短的片子也至少每秒 20 秒
        assertEquals(20L, SeekRamp.rate(6_000, 60_000))
    }

    @Test
    fun `a long gap between key events counts as at most 200 ms`() {
        assertEquals(10_000L + 20L * 200, SeekRamp.advance(10_000, true, 500, 5_000, film))
    }

    @Test
    fun `the target stays inside the video`() {
        assertEquals(0L, SeekRamp.advance(1_000, false, 6_000, 200, film))
        assertEquals(episode, SeekRamp.advance(episode - 1_000, true, 6_000, 200, episode))
    }
}
