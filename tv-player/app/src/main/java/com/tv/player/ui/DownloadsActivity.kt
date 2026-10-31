package com.tv.player.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tv.player.R
import com.tv.player.core.Downloads
import com.tv.player.core.Store

/** 离线下载的列表：下好的点一下就播（不用网络），下载中的显示进度，长按删除。和网络存储页共用行布局。 */
@OptIn(UnstableApi::class)
class DownloadsActivity : BaseActivity() {
    companion object {
        fun start(context: Context) = context.startActivity(Intent(context, DownloadsActivity::class.java))
    }

    private val handler = Handler(Looper.getMainLooper())
    private val adapter = NetStoreActivity.RowAdapter(::open, ::confirmRemove)
    private lateinit var status: TextView
    private lateinit var empty: View
    private var downloads: Map<String, Download> = emptyMap()
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pan)
        findViewById<TextView>(R.id.heading).text = "下载"
        status = findViewById(R.id.status)
        empty = findViewById(R.id.empty)
        empty.findViewById<ImageView>(R.id.emptyIcon).setImageResource(R.drawable.ic_download)
        empty.findViewById<TextView>(R.id.emptyTitle).text = "还没有下载"
        empty.findViewById<TextView>(R.id.emptySub).text = "播放时打开「设置」，点「下载这一集，离线看」"
        findViewById<View>(R.id.progress).visibility = View.GONE
        // 右上角的按钮在这里切换「流量下也下载」
        val toggle = findViewById<com.google.android.material.button.MaterialButton>(R.id.addShare)
        fun syncToggle() {
            toggle.text = if (Store.downloadOnMetered()) "流量下也下载：开" else "流量下也下载：关"
        }
        syncToggle()
        toggle.setOnClickListener {
            Downloads.setDownloadOnMetered(this, !Store.downloadOnMetered())
            syncToggle()
        }
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@DownloadsActivity)
            adapter = this@DownloadsActivity.adapter
            isFocusable = false
        }
        // 打开这页时把下载服务拉起来：上次没下完的（比如等 Wi-Fi 的）接着下
        runCatching { DownloadService.start(this, TvpDownloadService::class.java) }
        // 前台服务超时时下载被暂停过，进这页就恢复
        runCatching { DownloadService.sendResumeDownloads(this, TvpDownloadService::class.java, false) }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun refresh() {
        val list = Downloads.all(this)
        downloads = list.associateBy { it.request.id }
        adapter.submitList(list.map { d ->
            val info = Downloads.Info.of(d)
            val state = when (d.state) {
                Download.STATE_COMPLETED -> "已下载 · ${size(d.bytesDownloaded)}"
                Download.STATE_DOWNLOADING -> "下载中 ${d.percentDownloaded.toInt().coerceIn(0, 100)}% · ${size(d.bytesDownloaded)}"
                Download.STATE_QUEUED -> if (Store.downloadOnMetered()) "排队中" else "排队中（等 Wi-Fi）"
                Download.STATE_FAILED -> "下载失败，长按删除后重新下"
                Download.STATE_STOPPED -> "已暂停"
                Download.STATE_REMOVING -> "正在删除"
                else -> "准备中"
            }
            NetStoreActivity.Row(d.request.id, if (d.state == Download.STATE_COMPLETED) "影" else "下", "${info.title} ${info.episode}", state, null, null)
        })
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        status.text = if (list.isEmpty()) "" else "下载的内容占用 ${size(Downloads.usedBytes())}，长按一项可以删除"
    }

    private fun open(row: NetStoreActivity.Row) {
        val d = downloads[row.key] ?: return
        if (d.state != Download.STATE_COMPLETED) {
            Toast.makeText(this, "还没下完", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(CastActivity.offline(this, d.request.id, row.name))
    }

    private fun confirmRemove(row: NetStoreActivity.Row): Boolean {
        RemoteControl.cover(
            MaterialAlertDialogBuilder(this)
                .setTitle("删除「${row.name}」？")
                .setMessage("下载的文件会从手机上删掉。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除") { _, _ -> DownloadService.sendRemoveDownload(this, TvpDownloadService::class.java, row.key, false) }
                .show(),
        )
        return true
    }

    private fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(java.util.Locale.ROOT, bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f MB".format(java.util.Locale.ROOT, bytes / (1L shl 20).toDouble())
        else -> "${bytes / 1024} KB"
    }
}
