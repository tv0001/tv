package com.tv.player.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class CatchupUrlTest {
    // 2026-10-22 20:00 in Beijing (= 12:00 UTC), and the end of a 45-minute programme.
    private val start = Instant.parse("2026-10-22T12:00:00Z").toEpochMilli()
    private val end = start + 45 * 60_000L

    @Test
    fun `TVBox playseek placeholders are in UTC`() {
        val url = CatchupUrl.build("http://h/live.m3u8?playseek={utc:YmdHMS}-{utcend:YmdHMS}", start, end)
        assertEquals("http://h/live.m3u8?playseek=20261022120000-20261022124500", url)
    }

    @Test
    fun `Beijing time stamps in SimpleDateFormat style`() {
        val url = CatchupUrl.build("http://h/x/\${(b)yyyyMMddHHmmss}/\${(e)yyyyMMddHHmmss}.m3u8", start, end)
        assertEquals("http://h/x/20261022200000/20261022204500.m3u8", url)
    }

    @Test
    fun `Unix seconds`() {
        val url = CatchupUrl.build("http://h/p?s={utc}&e={utcend}", start, end)
        assertEquals("http://h/p?s=${start / 1000}&e=${end / 1000}", url)
    }

    @Test
    fun `local placeholders default to yyyyMMddHHmmss in Beijing time`() {
        assertEquals("20261022200000-20261022204500", CatchupUrl.build("{lutc}-{lutcend}", start, end))
        assertEquals("20261022200000", CatchupUrl.build("{lutc:YmdHMS}", start, end))
    }

    @Test
    fun `a pattern the formatter rejects falls back to seconds instead of throwing`() {
        val url = CatchupUrl.build("http://h/p?s={utc:YmdTHMS}", start, end)
        assertEquals("http://h/p?s=${start / 1000}", url)
    }

    @Test
    fun `a template without placeholders is kept as it is`() {
        assertEquals("http://h/plain.m3u8", CatchupUrl.build("http://h/plain.m3u8", start, end))
    }
}
