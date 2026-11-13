package com.tv.player.core

import com.tv.player.data.Episode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeMatchTest {
    private fun eps(vararg names: String) = names.map { Episode(it, "http://x/$it.m3u8") }

    @Test
    fun seriesWithDifferentSpellingsMatchByNumber() {
        val a = eps("第01集", "第02集", "第03集")
        assertEquals(2, EpisodeMatch.match(a, 2, eps("1", "2", "3")))
        assertEquals(2, EpisodeMatch.match(a, 2, eps("第1集", "第2集", "第3集")))
        assertEquals(0, EpisodeMatch.match(a, 1, eps("第2集HD", "第1集HD", "第3集HD")))
        // 那边少了第 1 集：按集数对，不按位置
        assertEquals(0, EpisodeMatch.match(a, 1, eps("第2集", "第3集")))
    }

    @Test
    fun varietyPartsDoNotJumpToAnotherVideo() {
        val split = eps("第1期上", "第1期下", "第2期上", "第2期下")
        val whole = eps("第1期", "第2期", "第3期", "第4期")
        // 第 2 期上（序号 2）在不拆分的源里没有对应：以前会跳到「第3期」
        assertNull(EpisodeMatch.match(split, 2, whole))
        assertNull(EpisodeMatch.match(whole, 1, split))
        // 写法不同的同一期
        assertEquals(2, EpisodeMatch.match(split, 2, eps("第1期(上)", "第1期(下)", "第2期（上）", "第2期（下）")))
        // 「第3期」和只写数字的「3」不算同一个：那边可能把上下拆开连着编号
        assertNull(EpisodeMatch.match(whole, 2, eps("1", "2", "3", "4")))
    }

    @Test
    fun extraEpisodesShiftPositionsButNamesStillMatch() {
        val a = eps("20261101期", "20261108期", "20261115期")
        val b = eps("20261101期", "20261101加更版", "20261108期", "20261108加更版", "20261115期")
        assertEquals(4, EpisodeMatch.match(a, 2, b))
        assertEquals(2, EpisodeMatch.match(b, 4, a))
        assertNull(EpisodeMatch.match(b, 3, a)) // 加更版那边没有
        assertEquals(1, EpisodeMatch.match(a, 1, eps("2026-11-01期", "2026-11-08期")))
    }

    @Test
    fun moviesAndNamelessListsFallBackToPosition() {
        assertEquals(0, EpisodeMatch.match(eps("正片"), 0, eps("HD国语")))
        assertEquals(1, EpisodeMatch.match(eps("第1集", "第2集"), 1, eps("高清", "高清")))
        assertEquals(1, EpisodeMatch.match(eps("", ""), 1, eps("a", "b")))
        assertNull(EpisodeMatch.match(eps("第1集", "第2集"), 1, eps("高清")))
        assertNull(EpisodeMatch.match(eps("第1集"), 3, eps("第1集")))
    }

    @Test
    fun finaleMarksEpPrefixAndRanges() {
        val a = eps("第01集", "第02集", "第03集完结")
        assertEquals(2, EpisodeMatch.match(a, 2, eps("1", "2", "3")))
        assertEquals(2, EpisodeMatch.match(eps("1", "2", "3"), 2, a))
        assertEquals(1, EpisodeMatch.match(a, 1, eps("EP01", "EP02", "EP03")))
        // 合集「第1-2集」不是「第12集」
        val merged = eps("第1-2集", "第3集")
        assertNull(EpisodeMatch.match(merged, 0, (1..12).map { Episode("第${it}集", "u$it") }))
        assertEquals(EpisodeMatch.key("20261108期"), EpisodeMatch.key("2026-11-08期"))
    }

    @Test
    fun keyIgnoresWidthCaseAndLeadingZeros() {
        assertEquals(EpisodeMatch.key("第０１集"), EpisodeMatch.key("1"))
        assertEquals(EpisodeMatch.key("EP01 HD"), EpisodeMatch.key("ep1"))
        assertEquals(3 to "集", EpisodeMatch.number("第03话"))
        assertEquals(3 to "期", EpisodeMatch.number("第3期"))
        assertNull(EpisodeMatch.number("第3期上"))
    }
}
