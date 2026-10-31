package com.tv.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class BrowseTest {
    @Test
    fun `the address carries the picked categories and leaves out 全部`() {
        val url = URLDecoder.decode(Browse.url("tv", linkedMapOf("类型" to "古装", "地区" to "全部"), "S", 30), "UTF-8")
        assertTrue(url.startsWith("https://m.douban.com/rexxar/api/v2/tv/recommend?"))
        assertTrue(url.contains("start=30&count=30"))
        assertTrue(url.contains("selected_categories={\"类型\":\"古装\"}"))
        assertTrue(url.contains("sort=S") && url.endsWith("tags=古装"))
    }

    @Test
    fun `items, categories and sorts are read from the answer`() {
        val page = Browse.parse(
            """{"total":500,"items":[
              {"title":"嫁金钗","type":"tv","card_subtitle":"2026 / 中国大陆 / 古装","rating":{"value":7.0},"pic":{"normal":"http://p/1.jpg"}},
              {"title":"广告","type":"ad"},
              {"title":"无评分","type":"movie","card_subtitle":"2025 / 美国","rating":null,"pic":{"large":"http://p/2.jpg"}}],
             "recommend_categories":[{"type":"类型","data":[{"text":"全部"},{"text":"古装"}]},{"type":"空的","data":[{"text":"全部"}]}],
             "sorts":[{"name":"T","text":"综合排序"},{"name":"S","text":"高分优先"}]}""",
        )!!
        assertEquals(listOf("嫁金钗", "无评分"), page.items.map { it.title })
        assertEquals(7.0, page.items[0].rating, 0.0)
        assertEquals("2026", page.items[0].sub)
        assertEquals("http://p/2.jpg", page.items[1].pic)
        assertEquals(listOf("类型"), page.categories.map { it.name })
        assertEquals(listOf("T" to "综合排序", "S" to "高分优先"), page.sorts)
        assertEquals(500, page.total)
        assertNull(Browse.parse("not json"))
    }

    @Test
    fun `the placeholder first option of the series form group is dropped`() {
        val page = Browse.parse("""{"items":[],"recommend_categories":[{"type":"类型","data":[{"text":"类型"},{"text":"电视剧"},{"text":"综艺"}]}]}""")!!
        assertEquals("形式", page.categories[0].name)
        assertEquals(listOf("电视剧", "综艺"), page.categories[0].options)
    }
}
