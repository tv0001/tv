package com.tv.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLogTest {
    @Test
    fun `addresses keep only their host`() {
        val text = "java.io.IOException: 403 on https://drive.example.com/s/ABC123sig/1.mp4?token=abc&sign=xyz rest"
        assertEquals("java.io.IOException: 403 on https://drive.example.com/… rest", CrashLog.redact(text))
        assertEquals("rtmp://live.example.com/… failed", CrashLog.redact("rtmp://user:pass@live.example.com:1935/app/stream failed"))
        assertEquals("no address here", CrashLog.redact("no address here"))
    }

    @Test
    fun `secret values outside addresses are masked`() {
        assertEquals("bad header Cookie: … and token=… end", CrashLog.redact("bad header Cookie: SID=abc123 and token=xyz end"))
        assertEquals("monkey=banana", CrashLog.redact("monkey=banana"))
    }

    @Test
    fun `a huge entry still keeps its header`() {
        val e = CrashLog.entry("deep", "x".repeat(100_000))
        assertEquals(1, CrashLog.count(CrashLog.trim(e)))
    }

    @Test
    fun `entries are counted by their header lines`() {
        val two = CrashLog.entry("a", "java.lang.IllegalStateException\n\tat x.y") + CrashLog.entry("b", "boom")
        assertEquals(2, CrashLog.count(two))
        assertEquals(0, CrashLog.count(""))
    }

    @Test
    fun `trimming drops the oldest entries whole`() {
        val log = (1..10).joinToString("") { CrashLog.entry("crash $it", "x".repeat(100)) }
        val kept = CrashLog.trim(log, 400)
        assertTrue(kept.length <= 400)
        assertTrue(kept.startsWith("---- "))
        assertTrue(kept.contains("crash 10"))
        assertFalse(kept.contains("crash 1\n"))
        assertEquals(log, CrashLog.trim(log, log.length))
    }
}
