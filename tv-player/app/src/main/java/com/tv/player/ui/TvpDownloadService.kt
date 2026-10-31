package com.tv.player.ui

import android.app.Notification
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.tv.player.R
import com.tv.player.core.Downloads

/** 下载期间的前台服务：通知栏显示进度，应用退到后台也接着下。 */
@UnstableApi
class TvpDownloadService : DownloadService(NOTIFY_ID, DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL, CHANNEL, R.string.download_channel, 0) {
    companion object {
        const val CHANNEL = "downloads"
        private const val NOTIFY_ID = 4201
    }

    private val helper by lazy { DownloadNotificationHelper(this, CHANNEL) }

    override fun getDownloadManager(): DownloadManager = Downloads.manager(this)

    // 不用系统调度：等不到 Wi-Fi 时下次打开应用再接着下
    override fun getScheduler(): Scheduler? = null

    /**
     * Android 15 起 dataSync 前台服务累计跑满 6 小时，系统会要求停下，不停整个应用就崩溃（Media3 1.6 的 DownloadService 没处理）。
     * 先暂停所有下载再停掉服务；下次进下载页或新加下载时会恢复。
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        downloadManager.pauseDownloads()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val text = when {
            notMetRequirements != 0 -> "等 Wi-Fi 连上再下载"
            downloads.isEmpty() -> "下载"
            else -> "正在下载 ${downloads.size} 个：" + downloads.joinToString("、") { Downloads.Info.of(it).let { i -> "${i.title} ${i.episode}" } }
        }
        return helper.buildProgressNotification(this, R.drawable.ic_download, null, text, downloads, notMetRequirements)
    }
}
