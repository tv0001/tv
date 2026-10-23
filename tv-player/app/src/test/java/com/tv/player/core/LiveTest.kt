package com.tv.player.core

import com.tv.player.data.Catchup
import com.tv.player.data.LiveChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTest {
    @Test
    fun `M3U channels keep their catchup source and days`() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="央视" catchup="default" catchup-days="5" catchup-source="http://x/p?s={utc:YmdHMS}&e={utcend:YmdHMS}",CCTV-1
            http://live/cctv1.m3u8
            #EXTINF:-1 group-title="央视",CCTV-2
            http://live/cctv2.m3u8
        """.trimIndent()
        val channels = Live.parse(m3u)
        assertEquals(listOf("CCTV-1", "CCTV-2"), channels.map { it.name })
        assertEquals(Catchup("http://x/p?s={utc:YmdHMS}&e={utcend:YmdHMS}", 5), channels[0].catchup)
        assertNull(channels[1].catchup)
    }

    @Test
    fun `a comma inside the catchup address does not cut the channel name`() {
        val m3u = "#EXTM3U\n#EXTINF:-1 catchup-source=\"http://x/a,{utc},{utcend}\",CCTV-3\nhttp://live/3.m3u8\n"
        val channel = Live.parse(m3u).single()
        assertEquals("CCTV-3", channel.name)
        assertEquals("http://x/a,{utc},{utcend}", channel.catchup?.template)
    }

    @Test
    fun `merged channel takes the catchup of whichever list has one`() {
        val plain = LiveChannel("CCTV-1", "央视", listOf("http://a/1.m3u8"))
        val replay = LiveChannel("CCTV-1", "央视", listOf("http://b/1.m3u8"), Catchup("http://b/p?{utc}", 3))
        val merged = Live.merge(listOf(listOf(plain), listOf(replay))).single()
        assertEquals(listOf("http://a/1.m3u8", "http://b/1.m3u8"), merged.urls)
        assertEquals(Catchup("http://b/p?{utc}", 3), merged.catchup)
    }

    @Test
    fun `quoted channel names keep their text`() {
        val m3u = "#EXTM3U\n#EXTINF:-1 group-title=\"央视\",\"CCTV-1\"\nhttp://live/1.m3u8\n"
        assertEquals("\"CCTV-1\"", Live.parse(m3u).single().name)
    }

    @Test
    fun `a catchup address without placeholders is not a replay`() {
        val m3u = "#EXTM3U\n#EXTINF:-1 catchup=\"append\" catchup-days=\"3\" catchup-source=\"?playseek=1\",CCTV-2\nhttp://live/2.m3u8\n"
        assertNull(Live.parse(m3u).single().catchup)
    }

    @Test
    fun `TVBox txt has no catchup and keeps every line`() {
        val txt = "央视,#genre#\nCCTV1,http://a/1.m3u8\$LR#http://b/1.m3u8\n"
        val channel = Live.parse(txt).single()
        assertEquals("CCTV1", channel.name)
        assertEquals(listOf("http://a/1.m3u8", "http://b/1.m3u8"), channel.urls)
        assertNull(channel.catchup)
    }
}
