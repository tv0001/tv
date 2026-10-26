package com.tv.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuarkWebTest {
    @Test
    fun `share links give the id and the pass code`() {
        assertEquals("fb787e3bd2a4" to "", QuarkWeb.parseShare("https://pan.quark.cn/s/fb787e3bd2a4"))
        assertEquals("fb787e3bd2a4" to "ab12", QuarkWeb.parseShare("https://pan.quark.cn/s/fb787e3bd2a4?pwd=ab12"))
        assertEquals("fb787e3bd2a4" to "x9Z3", QuarkWeb.parseShare("我用夸克网盘给你分享了「电影」\nhttps://pan.quark.cn/s/fb787e3bd2a4 提取码：x9Z3"))
        assertNull(QuarkWeb.parseShare("https://example.com/s/abc"))
        assertNull(QuarkWeb.parseShare(""))
    }

    @Test
    fun `disc images are not videos`() {
        assertTrue(QuarkWeb.isVideoName("Movie.2024.1080p.mkv"))
        assertTrue(QuarkWeb.isVideoName("a.MP4"))
        assertFalse(QuarkWeb.isVideoName("SUPERIOR_ULTRAMAN_8_BROTHERS.iso"))
        assertFalse(QuarkWeb.isVideoName("readme.txt"))
        assertFalse(QuarkWeb.isVideoName("noextension"))
    }

    @Test
    fun `folder names from share titles are safe`() {
        assertEquals("超奥特曼八兄弟.日版蓝光BD50@自购亲传", QuarkWeb.folderName("超奥特曼八兄弟.日版蓝光BD50@自购亲传"))
        assertEquals("a b c", QuarkWeb.folderName("a/b:c"))
        assertEquals("分享", QuarkWeb.folderName("   "))
        assertEquals(60, QuarkWeb.folderName("x".repeat(100)).length)
    }

    @Test
    fun `web links are drive links`() {
        assertTrue(QuarkWeb.isLink("quarkweb://abc"))
        assertTrue(Quark.isLink("quarkweb://abc"))
        assertTrue(Quark.isLink("quark://abc?q=high"))
        assertFalse(QuarkWeb.isLink("quark://abc"))
    }
}
