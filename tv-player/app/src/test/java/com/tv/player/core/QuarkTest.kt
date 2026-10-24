package com.tv.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class QuarkTest {
    @Test
    fun `episode links carry the file id and the quality`() {
        assertEquals("abc123" to "super", Quark.parseLink("quark://abc123?q=super"))
        assertEquals("abc123" to "download", Quark.parseLink("quark://abc123"))
        assertTrue(Quark.isLink("quark://x?q=high"))
        assertFalse(Quark.isLink("https://example.com/a.m3u8"))
    }

    @Test
    fun `the request token is the hash of method, path, time and key`() {
        val expected = MessageDigest.getInstance("SHA-256").digest("GET&/file&1700000000000&kw2dvtd7p4t3pjl2d9ed9yc8yej8kw2d".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(expected, Quark.signToken("GET", "/file", "1700000000000"))
    }

    @Test
    fun `an expired access token is recognised`() {
        assertTrue(Quark.tokenInvalid(-1, 10001, ""))
        assertTrue(Quark.tokenInvalid(-1, 11001, ""))
        assertTrue(Quark.tokenInvalid(400, 0, "Access Token expired"))
        assertTrue(Quark.tokenInvalid(0, 0, "token无效"))
        assertFalse(Quark.tokenInvalid(0, 0, ""))
        assertFalse(Quark.tokenInvalid(404, 0, "file not found"))
    }
}
