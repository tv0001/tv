package com.tv.player.ui

import com.tv.player.core.Library
import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodesTest {
    private fun entry(key: String, seen: Int, latest: Int) = Library.Entry(key, key, "", emptyList(), seen = seen, latest = latest)

    @Test
    fun `a series is announced once its count grows past the announced one`() {
        val entries = listOf(
            entry("grew", seen = 3, latest = 5), // 2 new, never announced
            entry("read", seen = 5, latest = 5), // nothing new to the user
            entry("announced", seen = 2, latest = 4), // 2 new, but already announced at 4
            entry("fresh-baseline", seen = 0, latest = 10), // first reading: no "new" episodes at all
        )
        val announced = mapOf("announced" to 4)
        val pending = Episodes.pending(entries) { announced[it] ?: 0 }
        assertEquals(listOf("grew"), pending.map { it.key })
    }

    @Test
    fun `a series with more episodes than before is announced again`() {
        val pending = Episodes.pending(listOf(entry("show", seen = 2, latest = 7))) { 4 }
        assertEquals(listOf("show"), pending.map { it.key })
    }
}
