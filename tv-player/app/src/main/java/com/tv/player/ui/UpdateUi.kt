package com.tv.player.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.tv.player.R
import com.tv.player.core.AppUpdate
import com.tv.player.core.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference

/**
 * The update dialogs. The download itself is not tied to a dialog: it runs in its own scope, so the dialog can be
 * put away ("后台下载") and the download goes on, reported in a notification; when it is done the install is
 * offered at once if the app is in front, otherwise through the notification and the next time the app is opened.
 * A download that breaks off keeps what it got and continues from there the next time.
 */
object UpdateUi {
    private const val CHANNEL = "updates"
    const val NOTIFY_ID = 2 // the progress (foreground service) notification; 1 is the playback one
    private const val DONE_ID = 3 // "downloaded" / "broke off": apart from the progress one, which the service removes

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())

    /** A finished download waiting for the user to allow installs from this app. */
    private var pending: File? = null

    /** The download finished while the app was not in front: offer the install when it is. */
    private var askOnResume = false

    /** The screen that last talked to us: where dialogs go when the download ends. */
    private var attached = WeakReference<AppCompatActivity>(null)

    private class Task(val info: AppUpdate.Info) {
        var job: Job? = null
        var done = 0L
        var total = 0L
        var dialog: AlertDialog? = null
        var bar: LinearProgressIndicator? = null
        var label: TextView? = null
        var lastNotified = 0L
    }

    /** The download in progress, if any; shared by the dialog and the notification. */
    private var task: Task? = null

    fun prompt(activity: AppCompatActivity, info: AppUpdate.Info) {
        attached = WeakReference(activity)
        task?.takeIf { it.info.code == info.code }?.let { showProgress(activity, it); return }
        activity.lifecycleScope.launch {
            val ready = withContext(Dispatchers.IO) { AppUpdate.ready(activity, info) }
            if (ready != null) askInstall(activity, info, ready) else offer(activity, info)
        }
    }

    private fun offer(activity: AppCompatActivity, info: AppUpdate.Info) {
        val partial = AppUpdate.partialBytes(activity, info)
        val size = if (info.size > 0) "大小 %.1f MB".format(java.util.Locale.ROOT, info.size / 1048576.0) else ""
        val got = if (partial > 0) "已下载 %.1f MB，会接着下".format(java.util.Locale.ROOT, partial / 1048576.0) else ""
        val notes = info.notes.ifBlank { "修复问题，改进体验" }
        MaterialAlertDialogBuilder(activity)
            .setTitle("发现新版本 ${info.name}")
            .setMessage(listOf(size, got).filter { it.isNotEmpty() }.joinToString("\n").let { if (it.isEmpty()) notes else "$notes\n\n$it" })
            .setCancelable(!info.force)
            .setPositiveButton(if (partial > 0) "继续下载" else "立即更新") { _, _ -> download(activity, info) }
            .apply {
                if (!info.force) {
                    setNegativeButton("稍后", null)
                    setNeutralButton("忽略此版本") { _, _ -> Store.setIgnoredVersion(info.code) }
                }
            }
            .show()
    }

    /** The file is already here (downloaded earlier, maybe in the background): no need to fetch it again. */
    private fun askInstall(activity: AppCompatActivity, info: AppUpdate.Info, file: File) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("新版本 ${info.name} 已下载好")
            .setMessage(info.notes.ifBlank { "修复问题，改进体验" })
            .setCancelable(!info.force)
            .setPositiveButton("安装") { _, _ -> installOrAsk(activity, file) }
            .apply { if (!info.force) setNegativeButton("稍后", null) }
            .show()
    }

    private fun download(activity: AppCompatActivity, info: AppUpdate.Info) {
        // One download at a time: the same version just shows its dialog again, another version replaces it.
        task?.let { running ->
            if (running.info.code == info.code) {
                showProgress(activity, running)
                return
            }
            running.job?.cancel()
        }
        val app = activity.applicationContext
        if (Build.VERSION.SDK_INT >= 33 && !NotificationManagerCompat.from(app).areNotificationsEnabled()) {
            ActivityCompat.requestPermissions(activity, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        NotificationManagerCompat.from(app).cancel(DONE_ID)
        val t = Task(info)
        task = t
        // Android 14+ cuts the network of an app that went to the background and became "cached"; a foreground
        // service keeps the download alive, and its notification is the one that shows the progress.
        runCatching { ContextCompat.startForegroundService(app, Intent(app, UpdateService::class.java)) }
        t.job = scope.launch {
            try {
                val file = AppUpdate.download(app, info) { done, total ->
                    t.done = done
                    t.total = total
                    main.post { refresh(app, t) }
                }
                if (task === t) {
                    task = null
                    stopService(app)
                }
                t.dialog?.dismiss()
                pending = file
                val act = attached.get()
                if (act != null && act.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    installOrAsk(act, file)
                } else {
                    askOnResume = true
                    notify(app, "新版本 ${info.name} 已下载好", "点这里安装", ongoing = false, id = DONE_ID)
                }
            } catch (e: CancellationException) {
                if (task === t) {
                    task = null
                    stopService(app)
                }
                t.dialog?.dismiss()
            } catch (e: Exception) {
                android.util.Log.d("TVP", "update download failed", e)
                if (task === t) {
                    task = null
                    stopService(app)
                }
                failed(app, t, e.message ?: "下载失败")
            }
        }
        showProgress(activity, t)
    }

    /** The progress dialog for [t]; "后台下载" puts it away and the notification takes over. */
    private fun showProgress(activity: AppCompatActivity, t: Task) {
        t.dialog?.let { old ->
            t.dialog = null // its dismiss listener must not touch the dialog built below
            old.dismiss()
        }
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_download, null)
        t.bar = view.findViewById(R.id.bar)
        t.label = view.findViewById(R.id.label)
        t.dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("正在下载 ${t.info.name}")
            .setView(view)
            .setCancelable(false)
            .setPositiveButton("后台下载", null)
            .setNegativeButton("取消") { _, _ -> t.job?.cancel() }
            .setOnDismissListener { d ->
                if (t.dialog !== d) return@setOnDismissListener // replaced by a newer dialog of the same task
                t.dialog = null
                t.bar = null
                t.label = null
                // Still running: from now on the notification shows the progress.
                if (task === t) refresh(activity.applicationContext, t, force = true)
            }
            .show()
        refresh(activity.applicationContext, t)
    }

    private fun refresh(app: Context, t: Task, force: Boolean = false) {
        val bar = t.bar
        if (bar != null) {
            if (t.total > 0) {
                bar.isIndeterminate = false
                bar.setProgressCompat((t.done * 100 / t.total).toInt(), true)
            }
            t.label?.text = "%.1f / %.1f MB".format(java.util.Locale.ROOT, t.done / 1048576.0, maxOf(t.total, t.done) / 1048576.0)
            return
        }
        // No dialog: a notification, updated about once a second so it does not flood the shade.
        val now = System.currentTimeMillis()
        if (!force && now - t.lastNotified < 1_000) return
        t.lastNotified = now
        val pct = if (t.total > 0) (t.done * 100 / t.total).toInt() else -1
        notify(app, "正在下载 ${t.info.name}", if (pct >= 0) "$pct%" else "", ongoing = true, progress = pct)
    }

    private fun failed(app: Context, t: Task, message: String) {
        t.dialog?.dismiss()
        val kept = AppUpdate.partialBytes(app, t.info)
        val detail = if (kept > 0) "$message\n已下载 %.1f MB，下次会接着下".format(java.util.Locale.ROOT, kept / 1048576.0) else message
        val act = attached.get()
        if (act != null && act.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            MaterialAlertDialogBuilder(act)
                .setTitle("下载中断")
                .setMessage(detail)
                .setPositiveButton("重试") { _, _ -> download(act, t.info) }
                .setNegativeButton("稍后", null)
                .show()
        } else {
            notify(app, "更新下载中断", "打开 TV Player 可以接着下", ongoing = false, id = DONE_ID)
        }
    }

    private fun installOrAsk(activity: AppCompatActivity, file: File) {
        if (!file.isFile) { // removed by a later download of another version
            pending = null
            return
        }
        if (AppUpdate.canInstall(activity)) {
            pending = null
            AppUpdate.install(activity, file)
            return
        }
        pending = file
        MaterialAlertDialogBuilder(activity)
            .setTitle("需要允许安装")
            .setMessage("下载已完成。需要先允许“${activity.getString(R.string.app_name)}”安装应用（只需要允许这一次），返回后会自动继续。")
            .setNegativeButton("取消") { _, _ -> pending = null }
            .setPositiveButton("去允许") { _, _ ->
                if (!AppUpdate.openInstallPermissionSettings(activity)) {
                    MaterialAlertDialogBuilder(activity)
                        .setTitle("请手动允许")
                        .setMessage("这台设备没有直达页面。请到 系统设置 → 应用 → 特殊应用权限（或 安全与限制）→ 安装未知应用，打开“${activity.getString(R.string.app_name)}”，然后重新点“检查更新”。")
                        .setPositiveButton("知道了", null)
                        .show()
                }
            }
            .show()
    }

    /**
     * Called from the main activity's onResume: continues an install the user just granted permission for, or offers
     * one that finished while the app was away.
     */
    fun resume(activity: AppCompatActivity) {
        attached = WeakReference(activity)
        val file = pending ?: return
        if (!file.isFile) {
            pending = null
            return
        }
        if (AppUpdate.canInstall(activity)) {
            pending = null
            askOnResume = false
            NotificationManagerCompat.from(activity).cancel(DONE_ID)
            AppUpdate.install(activity, file)
        } else if (askOnResume) {
            askOnResume = false
            NotificationManagerCompat.from(activity).cancel(DONE_ID)
            installOrAsk(activity, file)
        }
    }

    /** The service's time is up (Android 15 limits it): the download stops; what it got is kept for next time. */
    fun cancelDownload() {
        task?.job?.cancel()
    }

    /** The progress notification as it stands now, for the service to start in the foreground with. */
    fun progressNotification(app: Context): Notification {
        val t = task
        val pct = if (t != null && t.total > 0) (t.done * 100 / t.total).toInt() else -1
        return build(app, "正在下载 ${t?.info?.name ?: "更新"}", if (pct >= 0) "$pct%" else "", ongoing = true, progress = pct)
    }

    private fun stopService(app: Context) {
        app.stopService(Intent(app, UpdateService::class.java))
        cancelNotification(app)
    }

    private fun notify(app: Context, title: String, text: String, ongoing: Boolean, progress: Int = -1, id: Int = NOTIFY_ID) {
        val nm = NotificationManagerCompat.from(app)
        if (!nm.areNotificationsEnabled()) return // not allowed: the dialogs and the next start still cover it
        try {
            nm.notify(id, build(app, title, text, ongoing, progress))
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted on Android 13+: nothing to show, the dialogs still work
        }
    }

    private fun build(app: Context, title: String, text: String, ongoing: Boolean, progress: Int): Notification {
        app.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "应用更新", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_play_small)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setAutoCancel(!ongoing)
            .apply { if (progress >= 0) setProgress(100, progress, false) else if (ongoing) setProgress(0, 0, true) }
            .build()
    }

    private fun cancelNotification(app: Context) = NotificationManagerCompat.from(app).cancel(NOTIFY_ID)
}
