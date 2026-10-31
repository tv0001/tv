package com.tv.player.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.MimeTypes
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.util.Locale

/** Subtitle files the user brings in from storage. */
object Subtitles {
    private val gbk = Charset.forName("GBK")

    /**
     * The text of a subtitle file: UTF-16 when it starts with its byte order mark, else UTF-8 (a BOM is dropped), or GBK
     * when the bytes are not valid UTF-8 (many Chinese .srt files are).
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        val bom = bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        val body = if (bom) bytes.copyOfRange(3, bytes.size) else bytes
        return try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString()
        } catch (e: CharacterCodingException) {
            String(body, gbk)
        }
    }

    /** The player's type for a subtitle file, from its name; null for a format the player cannot show. */
    fun mimeOf(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        else -> null
    }

    /**
     * Copies a picked file into the app's cache as UTF-8, so the player reads it without needing the picker's
     * permission. Returns the file's name and the cached file's address, or null when it cannot be read.
     */
    fun load(context: Context, source: Uri): Pair<String, Uri>? {
        val name = displayName(context, source) ?: return null
        val bytes = context.contentResolver.openInputStream(source)?.use { it.readBytes() } ?: return null
        val dir = File(context.cacheDir, "subtitles").apply { mkdirs() }
        val file = File(dir, "${source.toString().hashCode()}.${name.substringAfterLast('.', "srt").lowercase()}")
        file.writeText(decode(bytes), Charsets.UTF_8)
        return name to Uri.fromFile(file)
    }

    // srt「00:00:01,000」、vtt「00:01.000」或「00:00:01.000」；ass 的「0:00:01.00」单独处理
    private val cueTime = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})([,.])(\d{3})""")
    // 时间行：两端都是完整的时间才算（正文里出现的「-->」不动）
    private val cueLine = Regex("""^\s*${cueTime.pattern}\s*-->\s*${cueTime.pattern}(\s.*)?$""")
    private val ssaTime = Regex("""(\d+):(\d{2}):(\d{2})\.(\d{2})""")

    /** 整个字幕的时间轴挪 [offsetMs]（正数＝字幕晚出现），挪到 0 之前的按 0 算。只改时间，别的原样保留。 */
    fun shift(text: String, mime: String?, offsetMs: Long): String {
        if (offsetMs == 0L) return text
        fun moved(ms: Long) = maxOf(0L, ms + offsetMs)
        return text.lineSequence().joinToString("\n") { line ->
            when {
                mime == MimeTypes.TEXT_SSA && line.startsWith("Dialogue:") -> {
                    // Dialogue: 层,开始,结束,其余
                    val parts = line.split(',', limit = 4)
                    if (parts.size < 4) line else {
                        val times = parts.subList(1, 3).map { t ->
                            ssaTime.matchEntire(t.trim())?.let { m ->
                                val (h, mi, s, cs) = m.destructured
                                val ms = moved(((h.toLong() * 60 + mi.toLong()) * 60 + s.toLong()) * 1000 + cs.toLong() * 10)
                                "%d:%02d:%02d.%02d".format(Locale.US, ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000 / 10)
                            } ?: t
                        }
                        listOf(parts[0], times[0], times[1], parts[3]).joinToString(",")
                    }
                }
                cueLine.matches(line) -> cueTime.replace(line) { m ->
                    val (h, mi, s, sep, milli) = m.destructured
                    val ms = moved(((h.ifEmpty { "0" }.toLong() * 60 + mi.toLong()) * 60 + s.toLong()) * 1000 + milli.toLong())
                    "%02d:%02d:%02d%s%03d".format(Locale.US, ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, sep, ms % 1000)
                }
                else -> line
            }
        }
    }

    /** 缓存里的字幕挪过时间后另存一份（文件名带偏移量），返回新地址；偏移为 0 就是原文件。 */
    fun shiftedCopy(original: Uri, mime: String?, offsetMs: Long): Uri {
        if (offsetMs == 0L) return original
        val src = File(original.path ?: return original)
        val out = File(src.parentFile, "${src.nameWithoutExtension}.o$offsetMs.${src.extension}")
        // 同一个字幕之前调过的副本用不上了
        src.parentFile?.listFiles { f -> f.name.startsWith("${src.nameWithoutExtension}.o") && f != out }?.forEach { it.delete() }
        out.writeText(shift(src.readText(), mime, offsetMs), Charsets.UTF_8)
        return Uri.fromFile(out)
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment
}
