package com.tv.player.core

import com.tv.player.data.VodGroup
import com.tv.player.data.VodItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ResultFilterTest {
    private fun group(title: String, remarks: String, year: String = "2024", source: String = "源A") = VodGroup(
        key = title,
        title = title,
        pic = "",
        remarks = "",
        items = listOf(VodItem("k", source, "1", title, "", remarks, year)),
    )

    private val all = listOf(
        group("甲", "高清"),
        group("乙", "枪版"),
        group("丙", "更新至12集", year = "2025"),
    )

    @Test
    fun `quality filter keeps high definition only`() {
        val f = ResultFilter().apply { quality = 1 }
        assertEquals(listOf("甲"), f.apply(all).map { it.title })
    }

    @Test
    fun `quality filter can drop preview copies`() {
        val f = ResultFilter().apply { quality = 2 }
        assertEquals(listOf("甲", "丙"), f.apply(all).map { it.title })
    }

    @Test
    fun `year filter and newest-first sort`() {
        val f = ResultFilter().apply { year = "2025" }
        assertEquals(listOf("丙"), f.apply(all).map { it.title })
        assertEquals(listOf("丙", "甲", "乙"), ResultFilter().apply { sort = 1 }.apply(all).map { it.title })
    }

    @Test
    fun `status filter tells finished from running`() {
        fun g(t: String, r: String) = group(t, r)
        val list = listOf(g("完", "全80集"), g("完2", "已完结"), g("完3", "正片"), g("更", "更新至第08集"), g("连", "连载中"), g("未知", "HD"), g("空", ""))
        assertEquals(listOf("完", "完2", "完3"), ResultFilter().apply { status = 1 }.apply(list).map { it.title })
        assertEquals(listOf("更", "连"), ResultFilter().apply { status = 2 }.apply(list).map { it.title })
        assertEquals(7, ResultFilter().apply(list).size)
        // a title one source calls finished and another still updating counts as finished
        val mixed = VodGroup("m", "混", "", "", listOf(VodItem("a", "A", "1", "混", "", "更新至第3集"), VodItem("b", "B", "2", "混", "", "全60集")))
        assertEquals(1, ResultFilter().statusOf(mixed))
    }

    @Test
    fun `status is not fooled by the words around it`() {
        fun status(r: String) = ResultFilter().statusOf(group("片", r))
        // finished although they say "更新" or "第N集"
        for (r in listOf("更新完毕", "更新完", "第80集(完)", "第80集（完）", "80集全", "80集完", "全集", "全54集（合全集）", "已完结")) assertEquals(r, 1, status(r))
        // running although they say "完结"
        for (r in listOf("未完结", "连载，未完结", "即将完结", "更新至第08集", "连载中", "第12集")) assertEquals(r, 2, status(r))
        for (r in listOf("", "HD", "高清", "抢先完整版")) assertEquals(r, 0, status(r))
    }

    @Test
    fun `status takes part in active and reset`() {
        val f = ResultFilter().apply { status = 2 }
        assertEquals(true, f.active)
        f.reset()
        assertEquals(false, f.active)
        assertEquals(0, f.status)
    }

    @Test
    fun `years are listed newest first`() {
        assertEquals(listOf("2025", "2024"), ResultFilter().years(all))
    }
}
