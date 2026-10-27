package com.tv.player.core

import org.junit.Assert.assertEquals
import org.junit.Test

class StallPolicyTest {
    @Test
    fun `nothing changes until something stalls`() {
        assertEquals(2_000L, StallPolicy().resumeMs())
    }

    @Test
    fun `each stall asks for more buffer, up to a ceiling`() {
        val p = StallPolicy()
        val seen = (1..6).map { p.onStall(); p.resumeMs() }
        assertEquals(listOf(3_500L, 5_000L, 6_500L, 7_000L, 7_000L, 7_000L), seen)
    }

    @Test
    fun `an episode without a stall takes a step back, never below the start`() {
        val p = StallPolicy()
        repeat(3) { p.onStall() }
        p.onSmoothEpisode()
        assertEquals(5_000L, p.resumeMs())
        repeat(5) { p.onSmoothEpisode() }
        assertEquals(0, p.stalls)
        assertEquals(2_000L, p.resumeMs())
    }
}
