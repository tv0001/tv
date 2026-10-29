package com.tv.player.ui

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Sends each request either through the disk cache or straight to the network. Addresses that must not be kept (a drive's
 * files: they expire and are tied to a login) bypass the cache; everything else on demand goes through it.
 */
@UnstableApi
class RoutingDataSource(
    private val direct: DataSource,
    private val cached: DataSource,
    private val bypass: (Uri) -> Boolean,
    /** For the addresses [isFile] picks (whole video files), a cached source of their own, when given. */
    private val fileCached: DataSource? = null,
    private val isFile: (Uri) -> Boolean = { false },
) : DataSource {
    private val listeners = ArrayList<TransferListener>()
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
        direct.addTransferListener(transferListener)
        cached.addTransferListener(transferListener)
        fileCached?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val chosen = when {
            bypass(dataSpec.uri) -> direct
            fileCached != null && isFile(dataSpec.uri) -> fileCached
            else -> cached
        }
        active = chosen
        return chosen.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active?.read(buffer, offset, length) ?: -1

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }

    class Factory(
        private val direct: DataSource.Factory,
        private val cached: DataSource.Factory,
        private val bypass: (Uri) -> Boolean,
        private val fileCached: DataSource.Factory? = null,
        private val isFile: (Uri) -> Boolean = { false },
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            RoutingDataSource(direct.createDataSource(), cached.createDataSource(), bypass, fileCached?.createDataSource(), isFile)
    }
}
