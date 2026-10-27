package com.tv.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTest {
    @Test
    fun `titles come out of the first JSON array in the answer`() {
        assertEquals(listOf("锦绣未央", "花千骨"), Ai.parseTitles("[\"锦绣未央\",\"花千骨\"]"))
        assertEquals(listOf("繁花"), Ai.parseTitles("好的，推荐：\n```json\n[\"繁花\"]\n```"))
        assertEquals(listOf("繁花"), Ai.parseTitles("[\"《繁花》\"]")) // brackets are cleaned like a typed keyword
        assertEquals(listOf("昼阳戏", "三体2"), Ai.parseTitles("[\"《昼阳戏？》\", \"三体2。\"]")) // stray marks at the ends go
    }

    @Test
    fun `unusable answers give no titles`() {
        assertEquals(emptyList<String>(), Ai.parseTitles(""))
        assertEquals(emptyList<String>(), Ai.parseTitles("[]"))
        assertEquals(emptyList<String>(), Ai.parseTitles("没有合适的片名"))
        assertEquals(emptyList<String>(), Ai.parseTitles("[\"未闭合"))
        assertEquals(emptyList<String>(), Ai.parseTitles("[1, 2, {\"a\": 1}, null]"))
    }

    @Test
    fun `only a real list of quoted strings is taken`() {
        assertEquals(emptyList<String>(), Ai.parseTitles("[注：仅供参考]")) // the lenient reader would take this for a title
        assertEquals(listOf("繁花"), Ai.parseTitles("[注：以下为推荐] [\"繁花\"]")) // a note before the answer
        assertEquals(emptyList<String>(), Ai.parseTitles("[\"无\", \"A\"]")) // single characters match everything
    }

    @Test
    fun `pasted links and codes are never sent`() {
        assertFalse(Ai.allowed("https://pan.quark.cn/s/abcdef123456"))
        assertFalse(Ai.allowed("提取码 a1b2c3d4e5f6g7"))
        assertTrue(Ai.allowed("女主重生的年代剧"))
        assertTrue(Ai.allowed("想看 Breaking Bad 那种"))
    }

    @Test
    fun `titles are kept short, plain, distinct and at most five`() {
        val long = "长".repeat(31)
        val many = (1..9).joinToString(",") { "\"片名$it\"" }
        assertEquals(5, Ai.parseTitles("[$many]").size)
        assertEquals(listOf("繁花"), Ai.parseTitles("[\"$long\", \"http://evil.example/x\", \"繁花\", \"繁 花\", \"\", \"繁花\"]"))
        assertEquals(listOf("正常"), Ai.parseTitles("[\"控制\\u0000字符\", \"正常\"]"))
    }

    @Test
    fun `the reply is read the OpenAI way`() {
        val reply = """{"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":"[\"繁花\"]","reasoning":"long thinking"}}]}"""
        assertEquals("[\"繁花\"]", Ai.contentOf(reply))
        assertEquals("", Ai.contentOf("not json"))
        assertEquals("", Ai.contentOf("""{"choices":[]}"""))
        assertEquals(listOf("繁花"), Ai.parseTitles(Ai.contentOf(reply)))
    }

    @Test
    fun `the request carries the text, privately, and nothing else`() {
        val body = Ai.requestBody("女主重生的年代剧")
        assertTrue("\"private\":true" in body)
        assertTrue("\"reasoning_effort\":\"low\"" in body)
        assertTrue("女主重生的年代剧" in body)
        assertEquals(100, org.json.JSONObject(Ai.requestBody("字".repeat(300))).getJSONArray("messages").getJSONObject(1).getString("content").length)
        assertFalse("key" in body.lowercase())
    }
}
