package com.tv.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelsTest {
    @Test
    fun `search results survive the trip through the saved JSON`() {
        val items = listOf(
            VodItem("s1", "源A", "42", "片名", "http://p/1.jpg", "高清", "2025", "剧情"),
            VodItem("s2", "源B", "7", "片名", "", "", ""),
        )
        assertEquals(items, vodItemsFromJson(items.toJsonString()))
    }
}
