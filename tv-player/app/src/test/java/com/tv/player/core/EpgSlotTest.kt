package com.tv.player.core

import org.junit.Assert.assertEquals
import org.junit.Test

class EpgSlotTest {
    @Test
    fun `programme times are minutes after the start of its day`() {
        val day = 1_000_000_000L
        val slot = Epg.Slot(start = 20 * 60, end = 21 * 60, title = "新闻", base = day)
        assertEquals(day + 20 * 60 * 60_000L, slot.startMs)
        assertEquals(day + 21 * 60 * 60_000L, slot.endMs)
    }

    @Test
    fun `a programme that runs past midnight ends on the next day`() {
        val slot = Epg.Slot(start = 23 * 60 + 30, end = 24 * 60 + 30, title = "夜场", base = 0L)
        assertEquals(60 * 60_000L, slot.endMs - slot.startMs)
    }
}
