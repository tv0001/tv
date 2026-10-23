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
    fun `years are listed newest first`() {
        assertEquals(listOf("2025", "2024"), ResultFilter().years(all))
    }
}
