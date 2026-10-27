package com.tv.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartTest {
    private fun parse(s: String) = Smart.parse(s).let { Triple(it.query, it.kind, it.year) }

    @Test
    fun `a sentence is taken apart into a keyword, a category and a year`() {
        assertEquals(Triple("重生", "短剧", ""), parse("想看一部女主重生的短剧"))
        assertEquals(Triple("悬疑", "电影", "2023"), parse("2023年的悬疑电影"))
        assertEquals(Triple("繁花", "", ""), parse("想看繁花"))
        assertEquals(Triple("繁花", "", ""), parse("有没有繁花"))
        assertEquals(Triple("重生", "短剧", ""), parse("短剧 重生"))
        assertEquals(Triple("重生", "短剧", ""), parse("女主重生的短剧"))
        assertEquals(Triple("庆余年", "电影", ""), parse("我想看庆余年的电影"))
    }

    @Test
    fun `bare descriptions fall back to the category, and a decade is not a year`() {
        assertEquals(Triple("电影", "电影", ""), parse("好看的电影"))
        assertEquals(Triple("短剧", "短剧", ""), parse("推荐几部好看的短剧"))
        assertEquals(Triple("电影", "电影", ""), parse("最新的电影"))
        assertEquals(Triple("1990年代的港剧", "", ""), parse("1990年代的港剧")) // "年代" is not "年"
        assertEquals(Triple("2012", "", ""), parse("2012")) // a bare year is a title
        assertEquals(Triple("1917", "", ""), parse("1917"))
    }

    @Test
    fun `what looks like a sentence keeps the text as typed to fall back on`() {
        // taking these apart cuts into a real title; the search tries the text as typed first, so it needs to be there
        assertEquals(Triple("回到夏天", "", "1980"), parse("回到1980年的夏天"))
        assertEquals(listOf("回到1980年的夏天"), Smart.parse("回到1980年的夏天").extras)
        assertEquals("者", Smart.parse("搜索者").query)
        assertEquals(listOf("搜索者"), Smart.parse("搜索者").extras)
    }

    @Test
    fun `plain titles are left exactly as typed`() {
        for (title in listOf("短剧之王", "我的前半生", "我的电影", "电影院", "繁花", "庆余年 第二季", "2012", "爱情公寓", "流浪地球2")) {
            val p = Smart.parse(title)
            assertEquals(title, p.query)
            assertEquals("", p.kind)
        }
    }

    @Test
    fun `a bare year or category is searched as it is`() {
        assertEquals("2012", Smart.parse("2012").query)
        assertEquals("短剧", Smart.parse("短剧").query)
        assertEquals("电影", Smart.parse("电影").query)
        assertEquals("2012", Smart.parse("2012 电影").query)
        assertEquals("电影", Smart.parse("2012 电影").kind)
    }

    @Test
    fun `descriptions are one more thing to try when nothing marks a sentence`() {
        val p = Smart.parse("女主重生")
        assertEquals("女主重生", p.query)
        assertEquals(listOf("重生"), p.extras)
        // and a taken-apart sentence keeps what was typed as the last resort
        assertEquals(listOf("想看一部女主重生的短剧"), Smart.parse("想看一部女主重生的短剧").extras)
    }

    @Test
    fun `the closest known title fixes a typo`() {
        val known = listOf("庆余年", "繁花", "流浪地球", "狂飙", "三体")
        assertEquals("流浪地球", Smart.nearest("流浪地求", known)) // one wrong character
        assertEquals("流浪地球", Smart.nearest("流浪地", known)) // one missing
        assertEquals("庆余年", Smart.nearest("庆余年年", known)) // one extra
        assertEquals("流浪地球", Smart.nearest("流浪地球球球", known)) // too far from anything but still within two
        assertNull(Smart.nearest("完全无关的词语", known))
    }

    @Test
    fun `nearest does not guess for short or exact input`() {
        val known = listOf("繁花", "狂飙", "三体", "庆余年")
        assertNull(Smart.nearest("繁华", known)) // two characters: a one-letter slip is half the title
        assertNull(Smart.nearest("庆余年", known)) // already a known title
        assertNull(Smart.nearest("庆余年", emptyList()))
        assertEquals("庆余年", Smart.nearest("庆余年季", known + "某剧"))
    }

    @Test
    fun `a sentence asking for a category puts those titles first without hiding any`() {
        fun item(t: String, kind: String, year: String = "") = com.tv.player.data.VodItem("a", "a", t, t, "", "", year, kind)
        val g = Grouper().also {
            it.add(listOf(item("重生之电影", "电影"), item("重生之我是短剧女王", "短剧", "2024"), item("重生", "剧集")))
        }
        // exact title "重生" is a series; the asker wanted a short drama, so that comes first, the rest stay in the list
        assertEquals(listOf("重生之我是短剧女王", "重生", "重生之电影"), g.snapshot("重生", kind = "短剧").map { it.title })
        assertEquals(3, g.snapshot("重生", kind = "短剧", year = "2024").size)
        // a year only decides between titles that match equally well: the exact title stays first
        assertEquals("重生", g.snapshot("重生", year = "2024").first().title)
        assertTrue(g.snapshot("重生").first().title == "重生")
        val tie = Grouper().also { it.add(listOf(item("重生之甲", "电影", "2019"), item("重生之乙", "电影", "2024"))) }
        assertEquals("重生之乙", tie.snapshot("重生", year = "2024").first().title)
        assertEquals("重生之甲", tie.snapshot("重生").first().title)
    }
}
