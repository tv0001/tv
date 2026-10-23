package com.tv.player.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.MimeTypes
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset

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

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment
}
