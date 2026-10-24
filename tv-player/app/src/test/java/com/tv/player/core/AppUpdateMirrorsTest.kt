package com.tv.player.core

import org.junit.Assert.assertEquals
import org.junit.Test

class AppUpdateMirrorsTest {
    @Test
    fun `a gh-proxy address gives the same file through every mirror`() {
        val apk = "https://gh-proxy.com/https://raw.githubusercontent.com/tv0001/tv/main/tv-player/releases/TV-Player-1.0.10.apk"
        assertEquals(
            listOf(
                apk,
                "https://ghfast.top/https://raw.githubusercontent.com/tv0001/tv/main/tv-player/releases/TV-Player-1.0.10.apk",
                "https://cdn.jsdelivr.net/gh/tv0001/tv@main/tv-player/releases/TV-Player-1.0.10.apk",
                "https://raw.githubusercontent.com/tv0001/tv/main/tv-player/releases/TV-Player-1.0.10.apk",
                "https://ghproxy.net/https://raw.githubusercontent.com/tv0001/tv/main/tv-player/releases/TV-Player-1.0.10.apk",
            ),
            AppUpdate.mirrorsOf(apk),
        )
    }

    @Test
    fun `any other address is used as it is`() {
        assertEquals(listOf("https://example.com/a.apk"), AppUpdate.mirrorsOf("https://example.com/a.apk"))
    }
}
