package com.tv.player.core

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URLEncoder
import java.nio.charset.Charset

/**
 * 搜索联想：输入框里每变一次，给出几个片名。本地的（热播榜、观看记录、收藏、搜索历史）按包含或拼音首字母匹配
 * （电视上用首字母键盘打「jjc」就能找到「嫁金钗」）；输入中文时再加上豆瓣的联想。
 */
object Suggest {
    // ---- 拼音首字母 ----------------------------------------------------------------------------------------------

    private val cache = HashMap<String, String>()

    /** 片名的拼音首字母（小写），字母和数字原样保留，其余符号跳过。 */
    @Synchronized
    fun initials(title: String): String = cache.getOrPut(title) {
        icuInitials(title) ?: title.mapNotNull(::gbInitial).joinToString("")
    }

    /** Android 10 起系统自带 ICU 的汉字转拼音，覆盖所有常用和不常用的字；之前的系统没有，返回 null。 */
    private fun icuInitials(title: String): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        return runCatching {
            val t = android.icu.text.Transliterator.getInstance("Han-Latin; Latin-ASCII")
            // 每个汉字转出来是一个音节，音节之间有空格；字母数字连着的部分整段保留
            title.split(Regex("(?<=\\p{IsHan})|(?=\\p{IsHan})")).filter { it.isNotBlank() }.joinToString("") { part ->
                if (part.first().let { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) t.transliterate(part).trim().take(1)
                else part.filter { it.isLetterOrDigit() && it.code < 128 }
            }.lowercase()
        }.getOrNull()
    }

    private val gbk = Charset.forName("GBK")
    private val starts = intArrayOf(
        0xB0A1, 0xB0C5, 0xB2C1, 0xB4EE, 0xB6EA, 0xB7A2, 0xB8C1, 0xB9FE, 0xBBF7, 0xBFA6, 0xC0AC, 0xC2E8,
        0xC4C3, 0xC5B6, 0xC5BE, 0xC6DA, 0xC8BB, 0xC8F6, 0xCBFA, 0xCDDA, 0xCEF4, 0xD1B9, 0xD4D1,
    )
    private const val LETTERS = "abcdefghjklmnopqrstwxyz"

    /**
     * 老系统上的退路：GB2312 一级字库（3755 个常用字）是按拼音排的，按码位就能查出首字母；二级字库里的字查不出来，跳过。
     */
    fun gbInitial(c: Char): Char? {
        if (c.code < 128) return if (c.isLetterOrDigit()) c.lowercaseChar() else null
        val b = c.toString().toByteArray(gbk)
        if (b.size != 2) return null
        val code = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
        if (code < 0xB0A1 || code > 0xD7F9) return null
        var i = starts.size - 1
        while (i > 0 && code < starts[i]) i--
        return LETTERS[i]
    }

    // ---- 匹配 ----------------------------------------------------------------------------------------------------

    private fun isLatin(q: String) = q.all { it.code < 128 && it.isLetterOrDigit() }

    /**
     * 从 [pool] 里挑和 [input] 匹配的片名：输入是字母数字时按首字母匹配（开头一样的排前面），否则按包含匹配。
     * [initialsOf] 默认用 [initials]，单测时可以换掉。
     */
    fun local(input: String, pool: Collection<String>, limit: Int = 8, initialsOf: (String) -> String = ::initials): List<String> {
        val q = input.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val scored = pool.asSequence().distinct().filter { it.isNotBlank() && it.lowercase() != q }.mapNotNull { title ->
            val lower = title.lowercase()
            when {
                isLatin(q) -> {
                    val ini = initialsOf(title)
                    when {
                        ini.startsWith(q) -> 0
                        lower.startsWith(q) -> 0
                        ini.contains(q) || lower.contains(q) -> 1
                        else -> null
                    }
                }
                lower.startsWith(q) -> 0
                lower.contains(q) -> 1
                else -> null
            }?.let { title to it }
        }.sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first.length })
        return scored.map { it.first }.take(limit).toList()
    }

    /** 豆瓣的联想（只在输入里有中文时查；首字母它不认）。 */
    suspend fun douban(input: String): List<String> = withContext(Dispatchers.IO) {
        val q = input.trim()
        if (q.isEmpty() || isLatin(q)) return@withContext emptyList()
        val r = Http.get(
            "https://movie.douban.com/j/subject_suggest?q=" + URLEncoder.encode(q, "UTF-8"),
            timeoutMs = 4_000,
            headers = mapOf("Referer" to "https://movie.douban.com/"),
        )
        if (r.status != 200) return@withContext emptyList()
        parseDouban(r.text)
    }

    fun parseDouban(text: String): List<String> = runCatching {
        val a = JSONArray(text)
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("title")?.trim()?.takeIf { t -> t.isNotEmpty() } }.distinct()
    }.getOrDefault(emptyList())
}
