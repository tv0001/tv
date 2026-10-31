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
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.tv.player.R
import com.tv.player.core.NetStore
import com.tv.player.data.VodGroup
import com.tv.player.data.VodItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 网络存储（WebDAV / SMB）：最上层是保存的存储（长按删除），进去按文件夹浏览，点一个视频就播放，同文件夹的视频是选集。
 * 页面和夸克网盘共用一套布局。
 */
class NetStoreActivity : BaseActivity() {
    companion object {
        fun start(context: Context) = context.startActivity(Intent(context, NetStoreActivity::class.java))
    }

    /** 一行：存储、文件夹或文件。 */
    class Row(val key: String, val mark: String, val name: String, val info: String, val server: NetStore.Server?, val entry: NetStore.Entry?)

    private lateinit var heading: TextView
    private lateinit var status: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private lateinit var addButton: MaterialButton
    private val adapter = RowAdapter(::open, ::confirmRemove)
    private var server: NetStore.Server? = null
    private val path = ArrayList<String>() // 进入的文件夹，空表示在存储列表
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pan)
        if (isTv()) findViewById<View>(R.id.panContent).setPadding(dp(48), dp(27), dp(48), dp(27))
        heading = findViewById(R.id.heading)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        addButton = findViewById(R.id.addShare)
        addButton.text = "添加网络存储"
        addButton.setOnClickListener { showAdd() }
        empty.findViewById<ImageView>(R.id.emptyIcon).setImageResource(R.drawable.ic_short)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.isFocusable = false
        onBackPressedDispatcher.addCallback(this) {
            when {
                path.size > 1 -> { path.removeAt(path.size - 1); load() }
                server != null -> { server = null; path.clear(); load() }
                else -> finish()
            }
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        if (server == null) load() // 手机网页可能刚添加了一个
    }

    private fun load() {
        job?.cancel()
        val s = server
        empty.visibility = View.GONE
        if (s == null) {
            heading.text = "网络存储"
            addButton.visibility = View.VISIBLE
            val servers = NetStore.servers()
            adapter.submitList(servers.map { Row(it.id, "存", it.name, (if (it.isSmb) "SMB · " else "WebDAV · ") + it.address, it, null) }) {
                // 遥控器先落在第一个存储上，而不是「添加网络存储」按钮
                if (isTv() && servers.isNotEmpty()) list.post { list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
            }
            status.text = if (servers.isEmpty()) "" else "长按一个存储可以删除"
            progress.visibility = View.GONE
            if (servers.isEmpty()) {
                empty.visibility = View.VISIBLE
                empty.findViewById<TextView>(R.id.emptyTitle).text = "还没有添加网络存储"
                empty.findViewById<TextView>(R.id.emptySub).text = if (isTv()) "点上面的「添加网络存储」，或者在「用手机输入或遥控电视」扫码后，用手机填写地址和密码"
                else "家里 NAS、路由器上的共享，或 OpenList / Alist 的 WebDAV 地址"
            }
            return
        }
        addButton.visibility = View.GONE
        val folder = path.last()
        heading.text = if (folder == "/") s.name else folder.trimEnd('/').substringAfterLast('/')
        status.text = "正在读取…"
        progress.visibility = View.VISIBLE
        job = lifecycleScope.launch {
            try {
                val entries = NetStore.list(s, folder)
                adapter.submitList(entries.map { e ->
                    Row(e.path, if (e.dir) "夹" else if (e.isVideo) "影" else "·", e.name, if (e.dir) "文件夹" else size(e.size) + if (e.isVideo) "" else " · 不是视频", s, e)
                }) {
                    list.scrollToPosition(0)
                    if (isTv()) list.post { list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
                }
                status.text = ""
                if (entries.isEmpty()) {
                    empty.visibility = View.VISIBLE
                    empty.findViewById<TextView>(R.id.emptyTitle).text = "这个文件夹是空的"
                    empty.findViewById<TextView>(R.id.emptySub).text = ""
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = "读取失败：${e.message ?: "连不上"}"
            }
            progress.visibility = View.GONE
        }
    }

    private fun open(row: Row) {
        val s = row.server ?: return
        val e = row.entry
        if (e == null) { // 一个存储
            server = s
            path.clear()
            path += "/"
            load()
            return
        }
        if (e.dir) {
            path += e.path
            load()
            return
        }
        if (!e.isVideo) {
            Toast.makeText(this, "这不是视频文件，或者是播放器不支持的格式", Toast.LENGTH_SHORT).show()
            return
        }
        // 文件夹是「一部片」，这个文件是从哪一集开始；播放页会再列一次这个文件夹
        val folder = path.last()
        val title = if (folder == "/") s.name else folder.trimEnd('/').substringAfterLast('/')
        val videos = adapter.currentList.mapNotNull { it.entry }.filter { it.isVideo }
        val group = VodGroup(
            "net:${s.id}:$folder", title, "", "",
            listOf(VodItem(NetStore.SOURCE_KEY, s.name, "${s.id}|$folder", title, "", "", kind = "网络存储")),
        )
        PlayActivity.startVod(this, group, startEp = videos.indexOfFirst { it.path == e.path }.coerceAtLeast(0))
    }

    private fun confirmRemove(row: Row): Boolean {
        val s = row.server ?: return false
        if (row.entry != null) return false
        RemoteControl.cover(
            MaterialAlertDialogBuilder(this)
                .setTitle("删除「${s.name}」？")
                .setMessage("只是从 TV Player 里去掉，存储上的文件不受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除") { _, _ ->
                    lifecycleScope.launch {
                        NetStore.remove(s.id)
                        load()
                    }
                }
                .show(),
        )
        return true
    }

    private fun showAdd() {
        val view = layoutInflater.inflate(R.layout.dialog_net_store, null)
        val kind = view.findViewById<ChipGroup>(R.id.kind)
        val hint = view.findViewById<TextView>(R.id.kindHint)
        val addressLayout = view.findViewById<TextInputLayout>(R.id.addressLayout)
        val statusView = view.findViewById<TextView>(R.id.addStatus)
        fun syncKind() {
            val smb = kind.checkedChipId == R.id.kindSmb
            hint.text = if (smb) "Windows 共享文件夹、大多数 NAS 的「SMB / 网上邻居」" else "OpenList / Alist（地址后面加 /dav）、群晖、威联通、路由器的 WebDAV"
            addressLayout.hint = if (smb) "地址，如 smb://192.168.1.10/movies" else "地址，如 http://192.168.1.10:5244/dav"
        }
        kind.setOnCheckedStateChangeListener { _, _ -> syncKind() }
        syncKind()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("添加网络存储")
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("连接并保存", null)
            .show()
        RemoteControl.cover(dialog)
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { button ->
            val text = { id: Int -> view.findViewById<TextInputEditText>(id).text?.toString().orEmpty() }
            button.isEnabled = false
            statusView.text = "正在连接…"
            lifecycleScope.launch {
                val error = NetStore.add(if (kind.checkedChipId == R.id.kindSmb) "smb" else "webdav", text(R.id.name), text(R.id.address), text(R.id.user), text(R.id.pass))
                button.isEnabled = true
                if (error == null) {
                    dialog.dismiss()
                    load()
                } else {
                    statusView.text = error
                }
            }
        }
    }

    private fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(java.util.Locale.ROOT, bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0f MB".format(java.util.Locale.ROOT, bytes / (1L shl 20).toDouble())
        else -> "${bytes / 1024} KB"
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    class RowAdapter(private val onClick: (Row) -> Unit, private val onLongClick: (Row) -> Boolean) : ListAdapter<Row, RowAdapter.Holder>(Diff) {
        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val mark: TextView = v.findViewById(R.id.avatar)
            val name: TextView = v.findViewById(R.id.name)
            val info: TextView = v.findViewById(R.id.lines)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val r = getItem(position)
            h.mark.text = r.mark
            h.name.text = r.name
            h.info.text = r.info
            h.itemView.setOnClickListener { onClick(r) }
            h.itemView.setOnLongClickListener { onLongClick(r) }
        }

        private object Diff : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = a.key == b.key
            override fun areContentsTheSame(a: Row, b: Row) = a.name == b.name && a.info == b.info
        }
    }
}
