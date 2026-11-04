package com.tv.player.ui

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.tv.player.core.AdFilter
import java.io.ByteArrayOutputStream

/**
 * 点播 m3u8 经过这里时按订阅的去广告规则剔除广告分片（见 [AdFilter]）。放在网络这一层，
 * 播放器、下一集预加载和分片预取看到的都是同一份清理后的列表。规则只看主机，跳转前后的地址都算。
 */
@UnstableApi
class AdFilterDataSource(
    private val upstream: DataSource,
    private val rules: () -> List<AdFilter.Rule>,
    private val onCleaned: (url: String, AdFilter.Result) -> Unit,
) : DataSource {
    private var data: ByteArray? = null
    private var pos = 0

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        data = null
        val length = upstream.open(dataSpec)
        // 只处理从头整份读取、路径以 .m3u8 结尾的列表：这类地址不进磁盘缓存（见 CachePolicy），不会出现缓存里是
        // 清理后的、上游补读的却是原始字节这种拼接错位
        if (dataSpec.position != 0L || dataSpec.length != C.LENGTH_UNSET.toLong() || !isPlaylist(dataSpec.uri)) return length
        val all = rules()
        val hosts = listOfNotNull(upstream.uri?.host, dataSpec.uri.host).map { it.lowercase() }.distinct()
        val host = hosts.firstOrNull { h -> all.any { it.matches(h) } } ?: return length
        val bytes = readAll()
        // 太大的不处理；规则或列表写得怪导致出错时也原样放，去广告不能让本来能播的片子放不了
        val cleaned = if (bytes.size > MAX_BYTES) null else runCatching { AdFilter.clean(String(bytes, Charsets.UTF_8), host, all) }.getOrNull()
        val out = cleaned?.text?.toByteArray(Charsets.UTF_8) ?: bytes
        cleaned?.let { onCleaned(dataSpec.uri.toString(), it) }
        data = out
        pos = 0
        return out.size.toLong()
    }

    private fun readAll(): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = upstream.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val d = data ?: return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        if (pos >= d.size) return C.RESULT_END_OF_INPUT
        val n = minOf(length, d.size - pos)
        System.arraycopy(d, pos, buffer, offset, n)
        pos += n
        return n
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        data = null
        upstream.close()
    }

    class Factory(
        private val upstream: DataSource.Factory,
        private val rules: () -> List<AdFilter.Rule>,
        private val onCleaned: (url: String, AdFilter.Result) -> Unit,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = AdFilterDataSource(upstream.createDataSource(), rules, onCleaned)
    }

    private companion object {
        const val MAX_BYTES = 8 * 1024 * 1024

        fun isPlaylist(uri: Uri) = uri.path.orEmpty().endsWith(".m3u8", ignoreCase = true)
    }
}
