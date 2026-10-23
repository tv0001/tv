package com.tv.player.ui

import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.tv.player.R
import com.tv.player.core.AppUpdate
import com.tv.player.core.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

object UpdateUi {
    /** A finished download waiting for the user to allow installs from this app. */
    private var pending: File? = null

    fun prompt(activity: AppCompatActivity, info: AppUpdate.Info) {
        val size = if (info.size > 0) "大小 %.1f MB".format(java.util.Locale.ROOT, info.size / 1048576.0) else ""
        val notes = info.notes.ifBlank { "修复问题，改进体验" }
        MaterialAlertDialogBuilder(activity)
            .setTitle("发现新版本 ${info.name}")
            .setMessage("$notes\n\n$size".trim())
            .setCancelable(!info.force)
            .setPositiveButton("立即更新") { _, _ -> download(activity, info) }
            .apply {
                if (!info.force) {
                    setNegativeButton("稍后", null)
                    setNeutralButton("忽略此版本") { _, _ -> Store.setIgnoredVersion(info.code) }
                }
            }
            .show()
    }

    private fun download(activity: AppCompatActivity, info: AppUpdate.Info) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_download, null)
        val bar = view.findViewById<LinearProgressIndicator>(R.id.bar)
        val label = view.findViewById<TextView>(R.id.label)
        var job: Job? = null
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("正在下载 ${info.name}")
            .setView(view)
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ -> job?.cancel() }
            .show()
        job = activity.lifecycleScope.launch {
            try {
                val file = AppUpdate.download(activity, info) { done, total ->
                    bar.post {
                        if (total > 0) {
                            bar.isIndeterminate = false
                            bar.setProgressCompat((done * 100 / total).toInt(), true)
                        }
                        label.text = "%.1f / %.1f MB".format(java.util.Locale.ROOT, done / 1048576.0, maxOf(total, done) / 1048576.0)
                    }
                }
                dialog.dismiss()
                installOrAsk(activity, file)
            } catch (e: CancellationException) {
                dialog.dismiss()
            } catch (e: Exception) {
                dialog.dismiss()
                Toast.makeText(activity, e.message ?: "下载失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun installOrAsk(activity: AppCompatActivity, file: File) {
        if (AppUpdate.canInstall(activity)) {
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

    /** Called from the main activity's onResume: continues an install the user just granted permission for. */
    fun resume(activity: AppCompatActivity) {
        val file = pending ?: return
        if (AppUpdate.canInstall(activity)) {
            pending = null
            AppUpdate.install(activity, file)
        }
    }
}
