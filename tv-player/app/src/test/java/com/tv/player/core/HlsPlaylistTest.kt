package com.tv.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class HlsPlaylistTest {
    private val resolve = { base: String, ref: String -> URL(URL(base), ref).toString() }

    @Test
    fun `a media playlist gives its segments in order, with their addresses and lengths`() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:4
            #EXTINF:3.52,
            seg000.ts
            #EXTINF:3.5,
            /other/seg001.ts
            #EXTINF:2,
            https://cdn.example/seg002.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val parsed = HlsPlaylist.parse(text, "https://a.test/v/index.m3u8", resolve) as HlsPlaylist.Parsed.Media
        assertEquals(
            listOf("https://a.test/v/seg000.ts", "https://a.test/other/seg001.ts", "https://cdn.example/seg002.ts"),
            parsed.segments.map { it.url },
        )
        assertEquals(listOf(3.52, 3.5, 2.0), parsed.segments.map { it.seconds })
    }

    @Test
    fun `a master playlist gives the playlists it points to`() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            low/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720

            high/index.m3u8
        """.trimIndent()
        val parsed = HlsPlaylist.parse(text, "https://a.test/v/master.m3u8", resolve) as HlsPlaylist.Parsed.Master
        assertEquals(listOf("https://a.test/v/low/index.m3u8", "https://a.test/v/high/index.m3u8"), parsed.variants)
    }

    @Test
    fun `what cannot be fetched ahead as whole files is left alone`() {
        val byteRange = "#EXTM3U\n#EXTINF:4,\n#EXT-X-BYTERANGE:1000@0\nall.ts\n#EXTINF:4,\n#EXT-X-BYTERANGE:1000@1000\nall.ts\n"
        assertSame(HlsPlaylist.Parsed.Unusable, HlsPlaylist.parse(byteRange, "https://a.test/i.m3u8", resolve))
        assertSame(HlsPlaylist.Parsed.Unusable, HlsPlaylist.parse("<html>login</html>", "https://a.test/i.m3u8", resolve))
        assertSame(HlsPlaylist.Parsed.Unusable, HlsPlaylist.parse("#EXTM3U\n#EXT-X-ENDLIST\n", "https://a.test/i.m3u8", resolve))
        assertSame(HlsPlaylist.Parsed.Unusable, HlsPlaylist.parse("", "https://a.test/i.m3u8", resolve))
    }

    @Test
    fun `an address that cannot be resolved makes the playlist unusable instead of throwing`() {
        val parsed = HlsPlaylist.parse("#EXTM3U\n#EXTINF:4,\nseg.ts\n", "https://a.test/i.m3u8") { _, _ -> error("bad") }
        assertSame(HlsPlaylist.Parsed.Unusable, parsed)
    }

    @Test
    fun `a missing length is zero seconds`() {
        val parsed = HlsPlaylist.parse("#EXTM3U\n#EXTINF:abc,\nseg.ts\n", "https://a.test/i.m3u8", resolve) as HlsPlaylist.Parsed.Media
        assertEquals(0.0, parsed.segments.single().seconds, 0.0)
    }

    @Test
    fun `a byte order mark in front of the playlist is no obstacle`() {
        val parsed = HlsPlaylist.parse("\uFEFF#EXTM3U\n#EXTINF:4,\nseg.ts\n", "https://a.test/i.m3u8", resolve)
        assertTrue(parsed is HlsPlaylist.Parsed.Media)
    }
}
