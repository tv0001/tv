package com.tv.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AdFilterTest {
    // 内置订阅（和线上订阅同一份规则）
    private val rules = AdFilter.parse(File("src/main/assets/best.json").readText())

    private fun seg(prefix: String, n: Int, dur: String = "6.0") = (0 until n).joinToString("") { "#EXTINF:$dur,\n$prefix$it.ts\n" }

    private fun vod(body: String) = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:8\n#EXT-X-MEDIA-SEQUENCE:0\n$body#EXT-X-ENDLIST\n"

    @Test
    fun parsesOnlyAdRules() {
        val names = rules.map { it.name }
        assertTrue(names.contains("非凡广告"))
        assertTrue(names.contains("量子广告"))
        assertTrue(names.contains("索尼")) // 按时长的规则
        assertFalse(names.any { it.contains("嗅探") }) // 嗅探规则不是广告规则
    }

    @Test
    fun removesFfzyAdBlock() {
        val ad = "#EXT-X-DISCONTINUITY\n" + seg("ad", 4, "3.0") + "#EXT-X-DISCONTINUITY\n"
        val text = vod(seg("a", 10) + ad + seg("b", 10))
        val r = AdFilter.clean(text, "vip.ffzy-play9.com", rules)
        assertNotNull(r)
        assertEquals(4, r!!.removed)
        assertFalse(r.text.contains("ad0.ts"))
        assertFalse(r.text.contains("#EXT-X-DISCONTINUITY"))
        assertTrue(r.text.contains("a9.ts") && r.text.contains("b0.ts") && r.text.trimEnd().endsWith("#EXT-X-ENDLIST"))
    }

    @Test
    fun removesBaofengAdjump() {
        // 暴风片源的真实样子：正片中间一段 /video/adjump/ 分片
        val ad = "#EXT-X-DISCONTINUITY\n" + (0 until 9).joinToString("") { "#EXTINF:3,\n/video/adjump/time/1793281984887000000$it.ts\n" } + "#EXT-X-DISCONTINUITY\n"
        val r = AdFilter.clean(vod(seg("0000", 300, "1") + ad + seg("1000", 300, "1")), "v.fengbao14.com", rules)
        assertEquals(9, r?.removed)
        assertFalse(r!!.text.contains("adjump"))
        assertTrue(r.text.contains("0000299.ts\n#EXT-X-DISCONTINUITY"))
    }

    @Test
    fun wildcardHostAndDurationRule() {
        // 索尼：按 15.1666 秒的时长识别；量子 vip.lz* 是通配符
        val ad = "#EXT-X-DISCONTINUITY\n" + seg("x", 1, "15.1666") + "#EXT-X-DISCONTINUITY\n"
        val r = AdFilter.clean(vod(seg("a", 10) + ad + seg("b", 10)), "m3u8.suonizy.com", rules)
        assertEquals(1, r?.removed)
        assertTrue(rules.first { it.name == "量子广告" }.matches("vip.lzcdn25.com"))
        assertFalse(rules.first { it.name == "量子广告" }.matches("vip.example.com"))
    }

    @Test
    fun leavesOtherHostsLiveAndOverbroadMatchesAlone() {
        val ad = "#EXT-X-DISCONTINUITY\n" + seg("ad", 4, "3.0") + "#EXT-X-DISCONTINUITY\n"
        val body = seg("a", 10) + ad + seg("b", 10)
        assertNull(AdFilter.clean(vod(body), "cdn.example.com", rules)) // 没有规则的主机
        assertNull(AdFilter.clean("#EXTM3U\n$body", "vip.ffzy-play9.com", rules)) // 直播（没有 ENDLIST）
        // 规则删掉超过一半时长：不像广告，原样放
        assertNull(AdFilter.clean(vod(seg("a", 1) + ad), "vip.ffzy-play9.com", rules))
    }

    @Test
    fun discontinuitySequenceIsNotABlock() {
        val text = "#EXTM3U\n#EXT-X-DISCONTINUITY-SEQUENCE:0\n" + seg("a", 10) + "#EXT-X-ENDLIST\n"
        assertNull(AdFilter.clean(text, "m3u8.suonizy.com", rules))
    }
}
