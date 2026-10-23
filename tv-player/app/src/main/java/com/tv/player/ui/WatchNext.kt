package com.tv.player.ui

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.BaseColumns
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.tv.player.core.Library

/**
 * The "continue watching" row on the Android TV home screen. Written only on TV: phones have no launcher row and the
 * provider is not there. Every write is best effort; a launcher that refuses it must not break playback.
 */
object WatchNext {
    private const val TAG = "TVP"
    private val uri get() = TvContractCompat.WatchNextPrograms.CONTENT_URI

    /** Shows the series at the episode and position it was left at. A series with nothing left to resume is removed. */
    fun publish(context: Context, e: Library.Entry, durationMs: Long) {
        if (!context.isTv()) return
        if (e.posMs <= 0) return remove(context, e.key)
        val intent = MainActivity.openIntent(context, e.key)
        val values = WatchNextProgram.Builder()
            .setType(TvContractCompat.PreviewPrograms.TYPE_TV_EPISODE)
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setTitle(e.title)
            .setDescription(if (e.epName.isNotEmpty()) "看到 ${e.epName}" else "看到第 ${e.ep + 1} 集")
            .setLastPlaybackPositionMillis(e.posMs.toInt())
            .apply { if (durationMs > 0) setDurationMillis(durationMs.toInt()) }
            .setLastEngagementTimeUtcMillis(e.time)
            .setInternalProviderId(e.key)
            .setIntentUri(Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME)))
            .build()
            .toContentValues()
        try {
            val resolver = context.contentResolver
            val id = rowId(context, e.key)
            if (id == null) resolver.insert(uri, values) else resolver.update(ContentUris.withAppendedId(uri, id), values, null, null)
        } catch (ex: Exception) {
            Log.d(TAG, "watch next: ${ex.message}")
        }
    }

    fun remove(context: Context, key: String) {
        if (!context.isTv()) return
        try {
            rowId(context, key)?.let { context.contentResolver.delete(ContentUris.withAppendedId(uri, it), null, null) }
        } catch (ex: Exception) {
            Log.d(TAG, "watch next: ${ex.message}")
        }
    }

    /** The launcher's row id of the series' program, found by the series key stored as its internal provider id. */
    private fun rowId(context: Context, key: String): Long? =
        context.contentResolver.query(uri, arrayOf(BaseColumns._ID, TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID), null, null, null)?.use { c ->
            var found: Long? = null
            while (c.moveToNext() && found == null) {
                if (c.getString(1) == key) found = c.getLong(0)
            }
            found
        }
}
