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
    fun localProxyOnlyTakesDirectLocalRequests() {
        val p = 9978
        assertTrue("jar / 播放器（OkHttp）的请求", SpiderProxy.allowed(mapOf("host" to "127.0.0.1:9978", "user-agent" to "okhttp/4.12.0", "range" to "bytes=0-"), p))
        assertTrue("没有 Host 的 HTTP/1.0 请求", SpiderProxy.allowed(emptyMap(), p))
        assertTrue(SpiderProxy.allowed(mapOf("host" to "localhost:9978"), p))
        assertFalse("网页脚本发来的", SpiderProxy.allowed(mapOf("host" to "127.0.0.1:9978", "origin" to "https://evil.example"), p))
        assertFalse("DNS 重绑定", SpiderProxy.allowed(mapOf("host" to "evil.example:9978"), p))
        assertFalse("别的端口", SpiderProxy.allowed(mapOf("host" to "127.0.0.1:9979"), p))
    }
}
