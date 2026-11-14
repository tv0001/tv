package com.tv.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnifferTest {
    private val page = "https://jx.example.com/?url=https://v.qq.com/x/cover/abc.html"

    @Test
    fun videoRequestsAreCaught() {
        assertTrue(Sniffer.isVideo("https://cdn.example.com/hls/2024/abc/index.m3u8?sign=1", page, emptyList()))
        assertTrue(Sniffer.isVideo("https://v9-x.example.com/abcdef/video/tos/cn/tos-cn-v-0000/xyz", page, emptyList()))
        assertTrue(Sniffer.isVideo("https://cdn.example.com/a/b/movie.mp4", page, emptyList()))
    }

    @Test
    fun theParsePageItselfIsNotAVideo() {
        assertFalse("解析页的地址里也带 m3u8，但它不是视频", Sniffer.isVideo("https://jx.example.com/?url=https://a.com/x.m3u8", page, emptyList()))
        assertFalse(Sniffer.isVideo("https://v.qq.com/x/cover/abc.html", page, emptyList()))
        assertFalse(Sniffer.isVideo("https://cdn.example.com/app.js", page, emptyList()))
    }

    @Test
    fun configRulesApplyByHost() {
        val rules = Sniffer.rules("""{"rules":[{"hosts":["jx.example.com"],"exclude":["ad.m3u8"],"regex":["/play?id="]}]}""")
        assertEquals(1, rules.size)
        assertFalse(Sniffer.isVideo("https://ads.example.net/x/ad.m3u8", page, rules))
        assertTrue(Sniffer.isVideo("https://media.example.net/play?id=42", page, rules))
        // 别的页面不受这条规则影响
        assertTrue(Sniffer.isVideo("https://ads.example.net/x/ad.m3u8", "https://other.example.org/", rules))
    }
}
