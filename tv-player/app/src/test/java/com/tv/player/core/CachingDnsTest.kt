package com.tv.player.core

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class CachingDnsTest {
    private val v6 = InetAddress.getByAddress("h", ByteArray(16).also { it[15] = 1 })
    private val v4a = InetAddress.getByAddress("h", byteArrayOf(10, 0, 0, 1))
    private val v4b = InetAddress.getByAddress("h", byteArrayOf(10, 0, 0, 2))

    private class Counting(var answer: () -> List<InetAddress>) : Dns {
        var lookups = 0
        override fun lookup(hostname: String): List<InetAddress> {
            lookups++
            return answer()
        }
    }

    @Test
    fun `IPv4 addresses come first and keep their order, IPv6 stays as a fallback`() {
        assertEquals(listOf(v4a, v4b, v6), CachingDns.ipv4First(listOf(v6, v4a, v4b)))
    }

    @Test
    fun `a host is looked up again only after the cache time`() {
        var now = 0L
        val sys = Counting { listOf(v6, v4a) }
        val dns = CachingDns(ttlMs = 1_000, delegate = sys, clock = { now })
        assertEquals(listOf(v4a, v6), dns.lookup("a.com"))
        now = 999
        dns.lookup("a.com")
        assertEquals(1, sys.lookups)
        now = 1_000
        dns.lookup("a.com")
        assertEquals(2, sys.lookups)
        dns.clear()
        dns.lookup("a.com")
        assertEquals(3, sys.lookups)
    }

    @Test
    fun `a failed lookup is not cached`() {
        val sys = Counting { throw UnknownHostException("a.com") }
        val dns = CachingDns(delegate = sys, clock = { 0L })
        assertThrows(UnknownHostException::class.java) { dns.lookup("a.com") }
        sys.answer = { listOf(v4a) }
        assertEquals(listOf(v4a), dns.lookup("a.com"))
        assertEquals(2, sys.lookups)
    }
}
