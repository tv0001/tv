package com.tv.player.ui

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Runs in the foreground while an update downloads, so the process is not treated as "cached" (which, from
 * Android 14 on, cuts its network a few seconds after the app leaves the screen). The notification it shows is the
 * download's progress; [UpdateUi] starts it with the download and stops it when the download ends.
 */
class UpdateService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = UpdateUi.progressNotification(this)
        if (Build.VERSION.SDK_INT >= 29) startForeground(UpdateUi.NOTIFY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(UpdateUi.NOTIFY_ID, notification)
        return START_NOT_STICKY
    }

    /** Android 15 stops a dataSync service after a few hours: give the download up rather than be killed. */
    override fun onTimeout(startId: Int) {
        UpdateUi.cancelDownload()
        stopSelf()
    }
}
