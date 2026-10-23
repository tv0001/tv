package com.tv.player.ui

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tv.player.R
import com.tv.player.core.Library
import com.tv.player.core.Store
import com.tv.player.core.Tracker
import java.util.concurrent.TimeUnit

/**
 * New episodes of favorites, checked in the background every hour (the app does not have to be open) and announced
 * with one notification per series. A series is announced again only when its count grows past the last announced one.
 */
object Episodes {
    private const val CHANNEL = "episodes"
    private const val UNIQUE_WORK = "episode-check"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<CheckWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Favorites with episodes the user has not seen yet, whose count has not been announced. */
    fun pending(entries: List<Library.Entry>, announced: (String) -> Int): List<Library.Entry> =
        entries.filter { it.fresh > 0 && it.latest > announced(it.key) }

    /** Posts the notifications. Does nothing when the user has turned notifications off for the app. */
    @SuppressLint("MissingPermission") // areNotificationsEnabled() is false until the permission is granted
    fun announce(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val system = context.getSystemService(NotificationManager::class.java)
        system.createNotificationChannel(NotificationChannel(CHANNEL, "追剧更新", NotificationManager.IMPORTANCE_DEFAULT))
        for (e in pending(Library.favorites(), Store::notifiedLatest)) {
            val open = PendingIntent.getActivity(
                context, e.key.hashCode(), MainActivity.openIntent(context, e.key),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val note = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_play_small)
                .setContentTitle(e.title)
                .setContentText("更新了 ${e.fresh} 集，共 ${e.latest} 集")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            manager.notify(e.key.hashCode(), note)
            Store.setNotifiedLatest(e.key, e.latest)
        }
    }
}

/** Runs [Episodes] in the background: look up the favorites' episode counts, then announce what is new. */
class CheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Tracker.check(force = true)
        Episodes.announce(applicationContext)
        return Result.success()
    }
}
