package com.tv.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CmsTest {
    @Test
    fun `short dramas are their own kind, not series`() {
        assertEquals("短剧", Cms.kindOf("短剧"))
        assertEquals("短剧", Cms.kindOf("爽文短剧"))
        assertEquals("短剧", Cms.kindOf("短剧大全"))
        assertEquals("剧集", Cms.kindOf("国产剧"))
        assertEquals("剧集", Cms.kindOf("连续剧"))
        assertEquals("电影", Cms.kindOf("喜剧片"))
        assertEquals("综艺", Cms.kindOf("大陆综艺"))
    }

    @Test
    fun `suggestive short-drama categories are filtered like adult ones`() {
        assertTrue(Cms.adult.containsMatchIn("擦边短剧"))
        assertTrue(Cms.adult.containsMatchIn("伦理片"))
        assertFalse(Cms.adult.containsMatchIn("短剧"))
        assertFalse(Cms.adult.containsMatchIn("爽文短剧"))
    }

    @Test
    fun `AI tags are stripped from titles`() {
        assertEquals("错位十五年", Cms.cleanTitle("错位十五年    AI"))
        assertEquals("读心后哥哥们把我宠上天", Cms.cleanTitle("读心后哥哥们把我宠上天    Ai漫剧"))
        assertEquals("我最亲爱的", Cms.cleanTitle("我最亲爱的(Ai)"))
        assertEquals("龙隐神医都市行", Cms.cleanTitle("龙隐神医都市行   AI "))
        assertEquals("随心舒适：大帝牧歌（第2季）", Cms.cleanTitle("随心舒适：大帝牧歌（第2季）"))
        assertEquals("AI", Cms.cleanTitle("AI")) // a title that is only the tag stays as it is
        assertEquals("Shanghai", Cms.cleanTitle("Shanghai")) // "ai" inside a word is not the tag
        assertEquals("错位十五年AI", Cms.cleanTitle("错位十五年AI")) // glued on without a space: left alone
        assertEquals("臣上欢", Cms.cleanTitle("臣上欢\$被穿越女霸占身体五年后")) // "$" joins an alternative title
    }
}
