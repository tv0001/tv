package com.tv.player.core

import com.tv.player.data.VodItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private fun item(title: String, source: String = "a") = VodItem(source, source, title, title, "", "")

    private fun ranked(keyword: String, vararg items: VodItem) =
        Grouper().also { it.add(items.toList()) }.snapshot(keyword).map { it.title }

    @Test
    fun `season numbers match whether they are written in Chinese or digits`() {
        assertEquals(Search.canon("庆余年 第二季"), Search.canon("庆余年第2季"))
        assertEquals("流浪地球第10集", Search.canon("流浪地球 第十集"))
        assertEquals("第23期", Search.canon("第二十三期"))
        assertEquals("第20部", Search.canon("第二十部"))
        assertEquals("第2季", Search.canon("第两季"))
        assertEquals("第一百季", Search.canon("第一百季")) // out of range: left as it is
        assertEquals("第十一二季", Search.canon("第十一二季"))
        assertEquals("第零季", Search.canon("第零季")) // there is no season zero to read
        assertTrue(Search.matches("庆余年第二季", "庆余年 第2季"))
        assertFalse(Search.matches("庆余年第二季", "庆余年 第3季"))
    }

    @Test
    fun `keyword is cleaned before it is sent`() {
        assertEquals("流浪地球2", Search.clean("  《流浪地球2》 "))
        assertEquals("繁花 胡歌", Search.clean("“繁花”　　胡歌"))
        assertEquals("ABC 123", Search.clean("ＡＢＣ  １２３"))
        // punctuation is sent as typed: a source compares letter by letter
        assertEquals("名侦探柯南：绯色的子弹", Search.clean("名侦探柯南：绯色的子弹"))
        assertEquals("Ⅱ，你好！", Search.clean("Ⅱ，你好！"))
        assertEquals("", Search.clean("《》"))
        assertEquals("Ocean's Eleven", Search.clean("Ocean's Eleven")) // an apostrophe is part of the name
    }

    @Test
    fun `relaxed queries drop the season and filler words`() {
        assertEquals(listOf("庆余年"), Search.relaxed("庆余年 第二季"))
        assertEquals(listOf("庆余年"), Search.relaxed("庆余年第2季全集"))
        assertEquals(listOf("繁花"), Search.relaxed("繁花 胡歌"))
        assertEquals(listOf("流浪地球"), Search.relaxed("流浪地球电影"))
        assertEquals(listOf("三体"), Search.relaxed("三体 S2"))
        assertEquals(listOf("庆余年"), Search.relaxed("庆余年第两季"))
        assertEquals(listOf("繁花 胡歌", "繁花"), Search.relaxed("繁花 胡歌 全集"))
    }

    @Test
    fun `relaxed queries never repeat the keyword and stop at three`() {
        assertEquals(emptyList<String>(), Search.relaxed("庆余年"))
        assertEquals(emptyList<String>(), Search.relaxed("  "))
        // a word that merely ends in "s1" is not a season mark
        assertEquals(emptyList<String>(), Search.relaxed("Mars1"))
        // Latin titles are not cut into pieces: "The" or "Spider" would only find unrelated ones
        assertEquals(emptyList<String>(), Search.relaxed("The Wandering Earth"))
        assertEquals(listOf("Spider Man"), Search.relaxed("Spider Man 全集"))
        assertEquals(listOf("天龙八部笑傲江湖", "天龙八部"), Search.relaxed("天龙八部笑傲江湖 全集 完整版"))
    }

    @Test
    fun `exact titles rank before prefixes before contains`() {
        assertEquals(
            listOf("庆余年", "庆余年第二季", "特别篇之庆余年"),
            ranked("庆余年", item("特别篇之庆余年"), item("庆余年第二季"), item("庆余年"), item("庆余年", "b")),
        )
    }

    @Test
    fun `season spelling decides the exact match`() {
        assertEquals("庆余年第2季", ranked("庆余年 第二季", item("庆余年"), item("庆余年第2季")).first())
    }

    @Test
    fun `within a tier more sources first, then the closer length`() {
        assertEquals(
            listOf("爱情公寓", "爱情公寓2", "爱情公寓5", "爱情公寓番外篇特别版"),
            ranked(
                "爱情公寓",
                item("爱情公寓番外篇特别版"), item("爱情公寓5"), item("爱情公寓2"), item("爱情公寓2", "b"), item("爱情公寓"),
            ),
        )
    }

    @Test
    fun `a retry's shorter query also decides the order`() {
        val g = Grouper().also { it.add(listOf(item("繁花1990", "a"), item("繁花1990", "b"), item("一路繁花", "a"), item("繁花", "a"))) }
        // typed "繁花 全集", found under "繁花": the title that is exactly "繁花" leads, although it is in fewer sources
        assertEquals("繁花", g.snapshot("繁花 全集", listOf("繁花")).first().title)
        assertEquals(listOf("繁花", "繁花1990", "一路繁花"), g.snapshot("繁花 全集", listOf("繁花")).map { it.title })
        // while the season that was asked for still counts as an exact title, and the shorter title is exact too
        val s2 = Grouper().also {
            it.add(listOf(item("庆余年"), item("庆余年第2季"), item("庆余年番外", "a"), item("庆余年番外", "b"), item("庆余年番外", "c")))
        }
        assertEquals(listOf("庆余年第2季", "庆余年番外", "庆余年"), s2.snapshot("庆余年 第二季").map { it.title })
        assertEquals(listOf("庆余年第2季", "庆余年", "庆余年番外"), s2.snapshot("庆余年 第二季", listOf("庆余年")).map { it.title })
    }

    @Test
    fun `recaps and trailers go last unless asked for`() {
        assertEquals(
            listOf("流浪地球2", "特别：流浪地球"),
            ranked("流浪地球", item("流浪地球2解说"), item("流浪地球2 预告"), item("流浪地球2"), item("特别：流浪地球")).take(2),
        )
        assertEquals("流浪地球解说", ranked("流浪地球 解说", item("流浪地球2"), item("流浪地球解说")).first())
    }
}
