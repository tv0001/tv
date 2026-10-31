package com.tv.player.ui

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.hierynomus.smbj.share.File as SmbFile
import com.tv.player.core.NetStore

/**
 * 读 SMB 共享里的文件（地址见 NetStore.playUrl）。播放器每次只要几十 KB，每次都发一个 SMB 请求太慢，所以一次读 512KB 攒着。
 */
@UnstableApi
class SmbDataSource : BaseDataSource(true) {
    private var file: SmbFile? = null
    private var uri: Uri? = null
    private var position = 0L // 下一个交给播放器的字节在文件里的位置
    private var remaining = 0L
    private var opened = false
    private var buffer = ByteArray(0) // 第一次打开 SMB 文件时才分配：这个数据源在每条播放链上都会建，绝大多数用不上
    private var bufferStart = 0L // buffer[0] 在文件里的位置
    private var bufferLength = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val (f, size) = try {
            NetStore.openSmb(dataSpec.uri.toString())
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        file = f
        if (buffer.isEmpty()) buffer = ByteArray(512 * 1024)
        if (dataSpec.position > size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) minOf(dataSpec.length, size - position) else size - position
        bufferLength = 0
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        if (position < bufferStart || position >= bufferStart + bufferLength) {
            val n = try {
                file!!.read(buffer, position, 0, minOf(buffer.size.toLong(), remaining).toInt())
            } catch (e: Exception) {
                throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            }
            if (n <= 0) return C.RESULT_END_OF_INPUT
            bufferStart = position
            bufferLength = n
        }
        val from = (position - bufferStart).toInt()
        val n = minOf(length, bufferLength - from, minOf(remaining, Int.MAX_VALUE.toLong()).toInt())
        System.arraycopy(buffer, from, target, offset, n)
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        runCatching { file?.close() }
        file = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}

/** tvpsmb:// 地址交给 SmbDataSource，其余照旧。 */
@UnstableApi
class SmbAwareDataSource(private val other: DataSource) : DataSource {
    private val listeners = ArrayList<TransferListener>()
    private val smbLazy = lazy { SmbDataSource().also { s -> listeners.forEach(s::addTransferListener) } }
    private val smb by smbLazy
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        other.addTransferListener(transferListener)
        listeners += transferListener
        if (smbLazy.isInitialized()) smb.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val chosen = if (dataSpec.uri.scheme == NetStore.SMB_SCHEME) smb else other
        active = chosen
        return chosen.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int) = active!!.read(buffer, offset, length)

    override fun getUri() = active?.uri

    override fun getResponseHeaders() = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }

    class Factory(private val other: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource() = SmbAwareDataSource(other.createDataSource())
    }
}
