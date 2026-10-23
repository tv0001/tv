package com.tv.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.charset.Charset

class SubtitlesTest {
    @Test
    fun `UTF-8 is read as it is`() {
        assertEquals("1\n00:00:01,000 --> 00:00:02,000\n你好", Subtitles.decode("1\n00:00:01,000 --> 00:00:02,000\n你好".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `a byte order mark is dropped`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "字幕".toByteArray(Charsets.UTF_8)
        assertEquals("字幕", Subtitles.decode(bytes))
    }

    @Test
    fun `GBK is read when the bytes are not valid UTF-8`() {
        val gbk = "你好，世界".toByteArray(Charset.forName("GBK"))
        assertEquals("你好，世界", Subtitles.decode(gbk))
    }

    @Test
    fun `UTF-16 files are read by their byte order mark`() {
        val little = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "字幕".toByteArray(Charsets.UTF_16LE)
        val big = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "字幕".toByteArray(Charsets.UTF_16BE)
        assertEquals("字幕", Subtitles.decode(little))
        assertEquals("字幕", Subtitles.decode(big))
    }

    @Test
    fun `only the formats the player can show are accepted`() {
        assertEquals("application/x-subrip", Subtitles.mimeOf("Movie.SRT"))
        assertEquals("text/vtt", Subtitles.mimeOf("a.vtt"))
        assertEquals("text/x-ssa", Subtitles.mimeOf("a.ass"))
        assertNull(Subtitles.mimeOf("notes.txt"))
        assertNull(Subtitles.mimeOf("noextension"))
    }
}
