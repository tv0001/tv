package com.tv.player.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.tv.player.R
import com.tv.player.core.Quark
import com.tv.player.data.VodGroup
import com.tv.player.data.VodItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 夸克网盘: the user's own files, folder by folder; a video plays with the rest of its folder as the episodes. */
class PanActivity : BaseActivity() {
    companion object {
        fun start(context: Context) = context.startActivity(Intent(context, PanActivity::class.java))
    }

    private lateinit var heading: TextView
    private lateinit var status: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private val adapter = PanAdapter(::open)

    /** The folders entered so far, root first. */
    private val path = ArrayList<Pair<String, String>>()
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pan)
        if (isTv()) findViewById<View>(R.id.panContent).setPadding(dp(48), dp(27), dp(48), dp(27)) // overscan-safe area
        heading = findViewById(R.id.heading)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        empty.findViewById<ImageView>(R.id.emptyIcon).setImageResource(R.drawable.ic_short)
        empty.findViewById<TextView>(R.id.emptyTitle).text = "这个文件夹是空的"
        empty.findViewById<TextView>(R.id.emptySub).text = "在手机的夸克 App 里把视频放进网盘，这里就能看到"
        findViewById<View>(R.id.addShare).setOnClickListener { ShareActivity.prompt(this) }
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.isFocusable = false // RecyclerView overrides the layout's focusable="false"; the rows take the focus
        empty.visibility = View.GONE // shown only after a folder turned out empty
        onBackPressedDispatcher.addCallback(this) {
            if (path.size > 1) {
                path.removeAt(path.size - 1)
                load()
            } else {
                finish()
            }
        }
        path += Quark.ROOT to Quark.SOURCE_NAME
        heading.text = Quark.SOURCE_NAME
        if (Quark.loggedIn) load() else PanLogin.show(this, onDone = ::load, onCancel = ::finish)
    }

    private fun load() {
        val (fid, name) = path.last()
        heading.text = name
        job?.cancel()
        status.text = "正在读取…"
        progress.visibility = View.VISIBLE
        empty.visibility = View.GONE
        job = lifecycleScope.launch {
            try {
                val files = Quark.list(fid)
                adapter.submitList(files) {
                    list.scrollToPosition(0)
                    // The "添加分享链接" button comes first in the layout; the remote should start on the files.
                    if (isTv()) list.post { list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
                }
                status.text = ""
                empty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Quark.ApiException) {
                status.text = e.message
                if (e.loggedOut) {
                    Quark.logout()
                    PanLogin.show(this@PanActivity, onDone = ::load, onCancel = ::finish)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // a newer folder is loading; its own status stands
            } catch (e: Exception) {
                status.text = "读取失败：${e.message ?: "网络不通"}"
            }
            progress.visibility = View.GONE
        }
    }

    private fun open(file: Quark.File) {
        if (file.isDir) {
            path += file.fid to file.name
            load()
            return
        }
        if (!file.isVideo) {
            Toast.makeText(this, "这不是视频文件", Toast.LENGTH_SHORT).show()
            return
        }
        // The folder is the series and this file the episode to start on; the player loads the folder's videos.
        val (folderFid, folderName) = path.last()
        val videos = adapter.currentList.filter { it.isVideo }
        val group = VodGroup(
            "quark:$folderFid", folderName, file.thumb, "",
            listOf(VodItem(Quark.SOURCE_KEY, Quark.SOURCE_NAME, folderFid, folderName, file.thumb, "", kind = "网盘")),
        )
        PlayActivity.startVod(this, group, startEp = videos.indexOfFirst { it.fid == file.fid }.coerceAtLeast(0))
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }
}

/** Folder and file rows; the same row as a live channel: a mark, the name, a line of details. */
class PanAdapter(private val onClick: (Quark.File) -> Unit) : ListAdapter<Quark.File, PanAdapter.Holder>(Diff) {
    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val mark: TextView = v.findViewById(R.id.avatar)
        val name: TextView = v.findViewById(R.id.name)
        val info: TextView = v.findViewById(R.id.lines)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun onBindViewHolder(h: Holder, position: Int) {
        val f = getItem(position)
        h.mark.text = if (f.isDir) "夹" else if (f.isVideo) "影" else "·"
        h.name.text = f.name
        h.info.text = if (f.isDir) "文件夹" else size(f.size) + (if (f.isVideo) "" else " · 不是视频")
        h.itemView.setOnClickListener { onClick(f) }
    }

    private fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(java.util.Locale.ROOT, bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f MB".format(java.util.Locale.ROOT, bytes / (1L shl 20).toDouble())
        else -> "${bytes / 1024} KB"
    }

    private object Diff : DiffUtil.ItemCallback<Quark.File>() {
        override fun areItemsTheSame(a: Quark.File, b: Quark.File) = a.fid == b.fid
        override fun areContentsTheSame(a: Quark.File, b: Quark.File) = a.name == b.name && a.size == b.size && a.updatedAt == b.updatedAt
    }
}
