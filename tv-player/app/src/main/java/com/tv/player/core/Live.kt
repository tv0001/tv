package com.tv.player.core

import com.tv.player.data.LiveChannel

object Live {
    private val groupTitle = Regex("group-title=\"([^\"]*)\"")

    fun parse(text: String): List<LiveChannel> =
        if (text.trimStart().startsWith("#EXTM3U")) parseM3u(text) else parseTxt(text)

    /** Channels sharing group and name become one channel with all their lines, first list first. */
    fun merge(lists: List<List<LiveChannel>>): List<LiveChannel> {
        val buckets = LinkedHashMap<String, Bucket>()
        for (c in lists.flatten()) buckets.getOrPut("${c.group}|${c.name}") { Bucket(c.name, c.group) }.urls += c.urls
        return toChannels(buckets.values)
    }

    private fun isHttp(u: String) = u.startsWith("http://") || u.startsWith("https://")

    private class Bucket(val name: String, val group: String) {
        val urls = LinkedHashSet<String>()
    }

    private fun toChannels(buckets: Collection<Bucket>) =
        buckets.filter { it.urls.isNotEmpty() }.map { LiveChannel(it.name, it.group, it.urls.toList()) }

    /** TVBox txt: `group,#genre#` starts a group, `name,url1#url2` lists channel lines (an optional `$label` is dropped). */
    private fun parseTxt(text: String): List<LiveChannel> {
        var group = "未分组"
        val buckets = LinkedHashMap<String, Bucket>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val comma = line.indexOf(',')
            if (comma <= 0) continue
            val name = line.substring(0, comma).trim()
            val rest = line.substring(comma + 1).trim()
            if (rest == "#genre#") {
                group = name
                continue
            }
            val bucket = buckets.getOrPut("$group|$name") { Bucket(name, group) }
            rest.split('#').map { it.substringBefore('$').trim() }.filter(::isHttp).forEach { bucket.urls += it }
        }
        return toChannels(buckets.values)
    }

    private fun parseM3u(text: String): List<LiveChannel> {
        val buckets = LinkedHashMap<String, Bucket>()
        var name = ""
        var group = "未分组"
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF")) {
                name = line.substringAfterLast(',').trim()
                group = groupTitle.find(line)?.groupValues?.get(1)?.ifBlank { null } ?: "未分组"
            } else if (isHttp(line) && name.isNotEmpty()) {
                buckets.getOrPut("$group|$name") { Bucket(name, group) }.urls += line
                name = ""
            }
        }
        return toChannels(buckets.values)
    }
}
