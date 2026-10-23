package com.tv.player.core

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Playback addresses for past programmes. An M3U line can give `catchup-source` with placeholders for the start and
 * end of the programme; this fills them in. Supported forms:
 *  - `${(b)yyyyMMddHHmmss}` / `${(e)…}`: start / end in Beijing time, pattern as in SimpleDateFormat
 *  - `{utc}` / `{utcend}`: start / end as Unix seconds; `{utc:YmdHMS}` the same time in UTC, `{utcend:…}` likewise
 *  - `{lutc}` / `{lutcend}`: start / end in Beijing time, `yyyyMMddHHmmss` unless a pattern is given
 */
object CatchupUrl {
    /** How far back a list may offer, whatever the playlist says. */
    const val MAX_DAYS = 7

    private val china = TimeZone.getTimeZone("Asia/Shanghai")
    private val utc = TimeZone.getTimeZone("UTC")
    private val stamp = Regex("""\$\{\(([be])\)([^}]+)\}""")
    private val token = Regex("""\{(utc|utcend|lutc|lutcend)(?::([^}]+))?\}""")

    fun build(template: String, startMs: Long, endMs: Long): String {
        val byStamp = stamp.replace(template) { m ->
            format(if (m.groupValues[1] == "b") startMs else endMs, m.groupValues[2], china)
        }
        return token.replace(byStamp) { m ->
            val name = m.groupValues[1]
            val at = if (name.endsWith("end")) endMs else startMs
            val pattern = m.groupValues[2]
            when {
                name.startsWith("lutc") -> format(at, if (pattern.isEmpty()) "yyyyMMddHHmmss" else letters(pattern), china)
                pattern.isEmpty() -> (at / 1000).toString()
                else -> format(at, letters(pattern), utc)
            }
        }
    }

    /** TVBox writes `YmdHMS` (M = minute, m = month); SimpleDateFormat wants `yyyyMMddHHmmss`. */
    private fun letters(pattern: String): String = buildString {
        for (c in pattern) append(
            when (c) {
                'Y' -> "yyyy"
                'm' -> "MM"
                'd' -> "dd"
                'H' -> "HH"
                'M' -> "mm"
                'S' -> "ss"
                else -> c.toString()
            },
        )
    }

    /** A pattern the formatter does not know (say a letter it reserves) falls back to Unix seconds, never an exception. */
    private fun format(ms: Long, pattern: String, zone: TimeZone): String = try {
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = zone }.format(ms)
    } catch (e: IllegalArgumentException) {
        (ms / 1000).toString()
    }
}
