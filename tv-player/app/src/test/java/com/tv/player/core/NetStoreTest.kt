package com.tv.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetStoreTest {
    private val dav = NetStore.Server("a1", "webdav", "家里", "http://192.168.1.1:5244/dav", "u", "p")

    @Test
    fun `an address belongs to a store only on the same host, port and root`() {
        assertTrue(NetStore.belongs("http://192.168.1.1:5244/dav/电影/a.mkv", dav))
        assertTrue(NetStore.belongs("http://192.168.1.1:5244/dav", dav))
        assertFalse(NetStore.belongs("http://192.168.1.10:5244/dav/a.mkv", dav)) // 只比前缀会当成同一台
        assertFalse(NetStore.belongs("http://192.168.1.1:5244/davx/a.mkv", dav))
        assertFalse(NetStore.belongs("https://192.168.1.1:5244/dav/a.mkv", dav))
        assertFalse(NetStore.belongs("http://cdn.example.com/dav/a.mkv", dav))
        assertTrue(NetStore.belongs("http://nas/x", dav.copy(address = "http://nas:80")))
    }

    @Test
    fun `a propfind answer lists the folder's children with relative paths`() {
        val xml = """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:">
            <D:response><D:href>/dav/%E6%B5%8B%E8%AF%95/</D:href><D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
            <D:response><D:href>http://192.168.1.1:5244/dav/%E6%B5%8B%E8%AF%95/%E7%AC%AC1%E9%9B%86.mp4</D:href><D:propstat><D:prop><D:getcontentlength>1024</D:getcontentlength><D:resourcetype/></D:prop></D:propstat></D:response>
            <D:response><D:href>/dav/%E6%B5%8B%E8%AF%95/%E5%AD%90/</D:href><D:propstat><D:prop><D:displayname>子</D:displayname><D:resourcetype><D:collection/></D:resourcetype></D:prop></D:propstat></D:response>
            </D:multistatus>"""
        val entries = NetStore.parseMultistatus(xml, "/dav", "/测试/")
        assertEquals(listOf("第1集.mp4", "子"), entries.map { it.name })
        assertEquals("/测试/第1集.mp4", entries[0].path)
        assertEquals(1024L, entries[0].size)
        assertTrue(entries[0].isVideo && entries[1].dir)
    }

    @Test
    fun `smb addresses are read in their usual spellings`() {
        assertEquals(NetStore.SmbAddress("192.168.1.10", 445, "movies", ""), NetStore.parseSmb("smb://192.168.1.10/movies"))
        assertEquals(NetStore.SmbAddress("nas", 4450, "share", "电影/新"), NetStore.parseSmb("\\\\nas:4450\\share\\电影\\新"))
        assertNull(NetStore.parseSmb("smb://only-host"))
    }

    @Test
    fun `names sort naturally and paths are encoded segment by segment`() {
        assertEquals(listOf("第1集", "第2集", "第10集"), listOf("第10集", "第2集", "第1集").sortedWith(NetStore::naturalCompare))
        assertEquals("/%E7%94%B5%E5%BD%B1/a%20b.mkv", NetStore.encodePath("/电影/a b.mkv"))
    }
}
