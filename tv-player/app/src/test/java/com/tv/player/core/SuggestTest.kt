package com.tv.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SuggestTest {
    @Test
    fun `common characters get their pinyin initial from the GB2312 order`() {
        assertEquals('a', Suggest.gbInitial('啊'))
        assertEquals('j', Suggest.gbInitial('金'))
        assertEquals('z', Suggest.gbInitial('最'))
        assertEquals('x', Suggest.gbInitial('西'))
        assertEquals('7', Suggest.gbInitial('7'))
        assertNull(Suggest.gbInitial('·'))
    }

    // 单测里没有安卓的 ICU，首字母用手写的表代替
    private val table = mapOf("嫁金钗" to "jjc", "金钗记" to "jcj", "小城良方" to "xclf", "西游记" to "xyj", "Friends 老友记" to "friendslyj")

    @Test
    fun `latin input matches by initials, starts first`() {
        assertEquals(listOf("嫁金钗"), Suggest.local("jjc", table.keys) { table.getValue(it) })
        assertEquals(setOf("金钗记", "嫁金钗"), Suggest.local("j", table.keys) { table.getValue(it) }.filter { it.contains("钗") }.toSet())
        assertEquals(listOf("Friends 老友记"), Suggest.local("FRI", table.keys) { table.getValue(it) })
    }

    @Test
    fun `chinese input matches by containing, the typed text itself is left out`() {
        assertEquals(listOf("金钗记", "嫁金钗"), Suggest.local("金钗", table.keys) { table.getValue(it) })
        assertEquals(emptyList<String>(), Suggest.local("西游记", listOf("西游记")) { "xyj" })
        assertEquals(emptyList<String>(), Suggest.local("  ", table.keys) { table.getValue(it) })
    }

    @Test
    fun `douban suggestions are read from its json`() {
        assertEquals(listOf("嫁金钗", "金钗"), Suggest.parseDouban("""[{"title":"嫁金钗","year":"2026"},{"title":"金钗"},{"title":"嫁金钗"},{"x":1}]"""))
        assertEquals(emptyList<String>(), Suggest.parseDouban("<html>"))
    }
}
