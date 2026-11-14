package com.tv.player.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PanSharesTest {
    @Test
    fun sharesAreFoundWithTheirCodes() {
        val html = """
            <a href="https://pan.quark.cn/s/abc123?pwd=x9y8">夸克</a>
            <p>https://pan.quark.cn/s/def456 提取码：k7m2</p>
            <a href="https://pan.quark.cn/s/abc123?pwd=x9y8">重复</a>
            <a href="https://pan.baidu.com/s/zzz">百度</a>
        """.trimIndent()
        assertEquals(listOf("abc123" to "x9y8", "def456" to "k7m2"), PanShares.findShares(html))
    }

    @Test
    fun theCodeIsKeptWhenTheLinkAppearsTwiceOrIsFollowedByText() {
        // <a href=链接>链接</a> 提取码：1234 ：同一个分享出现两次，码在第二次后面
        assertEquals(listOf("abc" to "1234"), PanShares.findShares("""<a href="https://pan.quark.cn/s/abc">https://pan.quark.cn/s/abc</a> 提取码：1234"""))
        // 链接后面紧跟中文
        assertEquals(listOf("xyz" to "5678"), PanShares.findShares("链接：https://pan.quark.cn/s/xyz提取码：5678"))
        assertEquals(listOf("qqq" to "9a9b"), PanShares.findShares("https://pan.quark.cn/s/qqq（提取码：9a9b）"))
    }

    @Test
    fun episodeNamesComeFromTheSeasonEpisodeTag() {
        assertEquals("第5集", PanShares.episodeName("[庆余年 第二季].Joy.of.Life.2024.S02E05.2160p.WEB-DL.mkv"))
        assertEquals("第12集", PanShares.episodeName("show.s01e12.1080p.mp4"))
        assertEquals("庆余年 第01集", PanShares.episodeName("庆余年 第01集.mp4"))
    }
}
