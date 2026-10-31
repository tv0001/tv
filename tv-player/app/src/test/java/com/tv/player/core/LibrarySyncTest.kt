package com.tv.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibrarySyncTest {
    @Test
    fun `a search reply carries the address and the device name`() {
        val reply = LibrarySync.searchReply("http://192.168.1.8:40321/sync", "TV Player（客厅）")
        val peer = LibrarySync.parseReply(reply)!!
        assertEquals("http://192.168.1.8:40321/sync", peer.url)
        assertEquals("TV Player（客厅）", peer.name)
    }

    @Test
    fun `other devices' replies are ignored`() {
        assertNull(LibrarySync.parseReply("HTTP/1.1 200 OK\r\nST: urn:schemas-upnp-org:service:AVTransport:1\r\nLOCATION: http://x/desc.xml\r\n\r\n"))
    }
}
