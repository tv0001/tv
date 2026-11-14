package com.tv.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpiderSecurityTest {
    @Test
    fun jarWithAWrongMd5IsNotLoaded() {
        val c = SpiderHost
        assertEquals(SpiderHost.JarChoice.DOWNLOADED, c.choose("aa", "", "aa"))
        assertEquals("没给 md5：用下载到的（只从 https 下）", SpiderHost.JarChoice.DOWNLOADED, c.choose("", "", "bb"))
        assertEquals("对不上、本地有：继续用本地的", SpiderHost.JarChoice.CACHED, c.choose("aa", "cc", "bb"))
        assertEquals("对不上、本地没有：不加载", SpiderHost.JarChoice.REFUSE, c.choose("aa", "", "bb"))
    }

    @Test
    fun aConfiguredMd5ThatCannotBeResolvedIsNotSkipped() {
        val md5 = "cbe016eb3cb002ae62058604843f5106"
        assertEquals("", SpiderHost.expectedMd5("https://x/spider.jar", null))
        assertEquals(md5, SpiderHost.expectedMd5("https://x/spider.jar;md5;${md5.uppercase()}", null))
        assertEquals(md5, SpiderHost.expectedMd5("https://x/spider.jar;md5;https://x/md5.txt", "$md5\n"))
        // 配了 md5，但取不到 / 取回的不是 md5 / md5 地址是 http：不能当成「没配」放行
        val unresolved = listOf(
            SpiderHost.expectedMd5("https://x/spider.jar;md5;https://x/md5.txt", null),
            SpiderHost.expectedMd5("https://x/spider.jar;md5;https://x/md5.txt", "<html>404</html>"),
            SpiderHost.expectedMd5("https://x/spider.jar;md5;http://x/md5.txt", null),
        )
        unresolved.forEach { assertEquals(SpiderHost.UNRESOLVED_MD5, it) }
        assertEquals(SpiderHost.JarChoice.CACHED, SpiderHost.choose(unresolved[0], "cc", "bb"))
        assertEquals(SpiderHost.JarChoice.REFUSE, SpiderHost.choose(unresolved[0], "", "bb"))
    }

    @Test
    fun localProxyOnlyTakesDirectLocalRequests() {
        val p = 9978
        assertTrue("jar / 播放器（OkHttp）的请求", SpiderProxy.allowed(mapOf("host" to "127.0.0.1:9978", "user-agent" to "okhttp/4.12.0", "range" to "bytes=0-"), p))
        assertTrue("没有 Host 的 HTTP/1.0 请求", SpiderProxy.allowed(emptyMap(), p))
        assertTrue(SpiderProxy.allowed(mapOf("host" to "localhost:9978"), p))
        assertFalse("网页脚本发来的", SpiderProxy.allowed(mapOf("host" to "127.0.0.1:9978", "origin" to "https://evil.example"), p))
        assertFalse("DNS 重绑定", SpiderProxy.allowed(mapOf("host" to "evil.example:9978"), p))
        assertFalse("网页里 <img src> 发来的（不带 Origin）", SpiderProxy.allowed(mapOf("host" to "127.0.0.1:9978", "sec-fetch-site" to "cross-site", "sec-fetch-dest" to "image"), p))
        assertTrue("播放器按爬虫要求带 Referer（B 站）", SpiderProxy.allowed(mapOf("host" to "127.0.0.1:9978", "referer" to "https://www.bilibili.com"), p))
        assertFalse("别的端口", SpiderProxy.allowed(mapOf("host" to "127.0.0.1:9979"), p))
    }
}
