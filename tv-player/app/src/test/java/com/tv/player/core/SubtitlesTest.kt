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

    @Test
    fun `srt and vtt cue times move by the offset`() {
        val srt = "1\n00:00:01,500 --> 00:00:03,000\n你好\n\n2\n00:59:59,800 --> 01:00:00,100\n再见"
        assertEquals(
            "1\n00:00:02,000 --> 00:00:03,500\n你好\n\n2\n01:00:00,300 --> 01:00:00,600\n再见",
            Subtitles.shift(srt, "application/x-subrip", 500),
        )
        // vtt 没有小时的写法也认，挪完补上小时；cue 设置原样保留
        assertEquals(
            "WEBVTT\n\n00:00:00.500 --> 00:00:01.000 line:90%\n字幕",
            Subtitles.shift("WEBVTT\n\n00:01.500 --> 00:02.000 line:90%\n字幕", "text/vtt", -1_000),
        )
    }

    @Test
    fun `times moved before zero become zero and text lines are untouched`() {
        assertEquals("00:00:00,000 --> 00:00:00,500\n12:34:56,789 是正文", Subtitles.shift("00:00:01,000 --> 00:00:02,500\n12:34:56,789 是正文", "application/x-subrip", -2_000))
        assertEquals("不动", Subtitles.shift("不动", "application/x-subrip", 0))
    }

    @Test
    fun `an arrow inside the text is not a cue time line`() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\n他说 00:00:05,000 --> 跑"
        assertEquals("1\n00:00:02,000 --> 00:00:03,000\n他说 00:00:05,000 --> 跑", Subtitles.shift(srt, "application/x-subrip", 1_000))
    }

    @Test
    fun `ass dialogue start and end move by the offset`() {
        val ass = "[Events]\nFormat: Layer, Start, End, Style, Text\nDialogue: 0,0:00:01.00,0:00:02.50,Default,,0,0,0,,你好,世界"
        assertEquals(
            "[Events]\nFormat: Layer, Start, End, Style, Text\nDialogue: 0,0:00:02.50,0:00:04.00,Default,,0,0,0,,你好,世界",
            Subtitles.shift(ass, "text/x-ssa", 1_500),
        )
    }
}
