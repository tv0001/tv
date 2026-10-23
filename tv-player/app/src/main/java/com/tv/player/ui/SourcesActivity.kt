package com.tv.player.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.tv.player.R
import com.tv.player.core.Http
import com.tv.player.core.Live
import com.tv.player.core.Probe
import com.tv.player.core.Store
import com.tv.player.data.VodSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

class SourcesActivity : AppCompatActivity() {
    private class Row(
        val key: String,
        val title: String,
        val sub: String,
        val custom: Boolean,
        val enabled: Boolean?,
        val status: String?,
        val statusOk: Boolean,
    ) {
        fun same(o: Row) = key == o.key && title == o.title && sub == o.sub && custom == o.custom &&
            enabled == o.enabled && status == o.status && statusOk == o.statusOk
    }

    private var live = false
    private val results = HashMap<String, Pair<String, Boolean>>()
    private lateinit var adapter: RowAdapter
    private lateinit var hint: TextView
    private lateinit var testAll: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sources)
        hint = findViewById(R.id.hint)
        testAll = findViewById(R.id.testAll)
        findViewById<View>(R.id.back).setOnClickListener { finish() }

        adapter = RowAdapter(
            onToggle = { row, on ->
                Store.setEnabled(row.key, on)
                refresh()
            },
            onDelete = ::confirmDelete,
            onClick = { row -> if (!live) testOne(row.key) },
        )
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@SourcesActivity)
            adapter = this@SourcesActivity.adapter
            itemAnimator = null
        }
        findViewById<ChipGroup>(R.id.mode).setOnCheckedStateChangeListener { _, ids ->
            live = ids.firstOrNull() == R.id.chipLive
            refresh()
        }
        testAll.setOnClickListener { testEverything() }
        findViewById<View>(R.id.add).setOnClickListener { showAdd() }
        refresh()
    }

    private fun rows(): List<Row> {
        if (live) {
            val builtIn = Row("builtin", "订阅直播源", "${Live.parse(Store.liveText()).size} 个频道 · 来自订阅目录的 live.txt", false, null, null, false)
            return listOf(builtIn) + Store.customLives().map {
                Row(it.url, it.name, "${Live.parse(it.text).size} 个频道 · ${it.url}", true, null, null, false)
            }
        }
        val off = Store.disabled()
        val custom = Store.customSites().map { it.key }.toSet()
        return Store.allSources().map { s ->
            val r = results[s.key]
            Row(s.key, s.name + if (s.key in custom) "（自定义）" else "", s.api, s.key in custom, s.key !in off, r?.first, r?.second ?: false)
        }
    }

    private fun refresh() {
        hint.text = if (live) {
            "自定义直播列表会和订阅里的频道合并：同名频道的线路会加到一起。"
        } else {
            val total = Store.allSources().size
            "共 $total 个点播源，已启用 ${total - Store.allSources().count { it.key in Store.disabled() }} 个。点一行单独测速，开关控制是否参与搜索。"
        }
        testAll.visibility = if (live) View.GONE else View.VISIBLE
        adapter.submitList(rows())
    }

    private fun format(r: Probe.Result) = r.describe() to r.ok

    private fun testOne(key: String) {
        val source = Store.allSources().firstOrNull { it.key == key } ?: return
        results[key] = "检测中…" to true
        refresh()
        lifecycleScope.launch {
            results[key] = format(Probe.vod(source))
            refresh()
        }
    }

    private fun testEverything() {
        val sources = Store.allSources()
        sources.forEach { results[it.key] = "检测中…" to true }
        refresh()
        testAll.isEnabled = false
        lifecycleScope.launch {
            sources.map { s ->
                async {
                    results[s.key] = format(Probe.vod(s))
                    refresh()
                }
            }.awaitAll()
            testAll.isEnabled = true
            val ok = sources.count { results[it.key]?.second == true }
            toast("测速完成：$ok/${sources.size} 个可用")
        }
    }

    private fun showAdd() {
        val view = layoutInflater.inflate(R.layout.dialog_add_source, null)
        val name = view.findViewById<TextInputEditText>(R.id.name)
        val url = view.findViewById<TextInputEditText>(R.id.url)
        view.findViewById<TextInputLayout>(R.id.urlLayout).hint =
            if (live) "直播列表地址（txt / m3u）" else "接口地址，如 https://xxx/api.php/provide/vod"
        MaterialAlertDialogBuilder(this)
            .setTitle(if (live) "添加自定义直播源" else "添加自定义点播源")
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("检测并添加") { _, _ ->
                if (live) addLive(name.text.toString(), url.text.toString()) else addVod(name.text.toString(), url.text.toString())
            }
            .show()
    }

    private fun busy(message: String) = MaterialAlertDialogBuilder(this).setMessage(message).setCancelable(false).show()

    private fun addVod(name: String, api: String) {
        val clean = api.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            toast("请输入 http(s) 开头的接口地址")
            return
        }
        val key = clean.trimEnd('/')
        val progress = busy("正在检测这个源能不能搜索…")
        lifecycleScope.launch {
            val r = Probe.vod(VodSource(key, name.trim().ifEmpty { clean }, clean))
            progress.dismiss()
            if (r.ok) {
                Store.addCustomSite(name, clean)
                results[key] = format(r)
                refresh()
                toast("已添加：${r.describe()}")
            } else {
                MaterialAlertDialogBuilder(this@SourcesActivity)
                    .setTitle("检测没通过")
                    .setMessage("${r.error}\n\n仍然添加吗？之后可以随时在这里删除。")
                    .setNegativeButton("不添加", null)
                    .setPositiveButton("仍然添加") { _, _ ->
                        Store.addCustomSite(name, clean)
                        results[key] = format(r)
                        refresh()
                    }
                    .show()
            }
        }
    }

    private fun addLive(name: String, url: String) {
        val clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            toast("请输入 http(s) 开头的地址")
            return
        }
        val progress = busy("正在下载并解析…")
        lifecycleScope.launch {
            val r = Http.get(clean, timeoutMs = 25_000)
            progress.dismiss()
            val count = if (r.status == 200) Live.parse(r.text).size else 0
            if (count > 0) {
                Store.addCustomLive(name, clean, r.text)
                refresh()
                toast("已添加：$count 个频道")
            } else {
                MaterialAlertDialogBuilder(this@SourcesActivity)
                    .setTitle("没有添加")
                    .setMessage(if (r.status == 200) "地址能打开，但没解析出频道，需要 TXT（名称,地址）或 M3U 格式" else "下载失败：${r.error ?: "HTTP ${r.status}"}")
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
    }

    private fun confirmDelete(row: Row) {
        MaterialAlertDialogBuilder(this)
            .setTitle("删除「${row.title}」？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                if (live) Store.removeCustomLive(row.key) else Store.removeCustomSite(row.key)
                results.remove(row.key)
                refresh()
            }
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    private class RowAdapter(
        private val onToggle: (Row, Boolean) -> Unit,
        private val onDelete: (Row) -> Unit,
        private val onClick: (Row) -> Unit,
    ) : ListAdapter<Row, RowAdapter.Holder>(Diff) {

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.title)
            val sub: TextView = v.findViewById(R.id.sub)
            val status: TextView = v.findViewById(R.id.status)
            val toggle: MaterialSwitch = v.findViewById(R.id.toggle)
            val delete: ImageButton = v.findViewById(R.id.delete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_source, parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val row = getItem(position)
            h.title.text = row.title
            h.sub.text = row.sub
            h.status.visibility = if (row.status == null) View.GONE else View.VISIBLE
            h.status.text = row.status
            h.status.setTextColor(h.itemView.context.getColor(if (row.statusOk) R.color.ok else R.color.bad))
            h.toggle.setOnCheckedChangeListener(null)
            h.toggle.visibility = if (row.enabled == null) View.GONE else View.VISIBLE
            h.toggle.isChecked = row.enabled ?: false
            h.toggle.setOnCheckedChangeListener { _, on -> onToggle(row, on) }
            h.delete.visibility = if (row.custom) View.VISIBLE else View.GONE
            h.delete.setOnClickListener { onDelete(row) }
            h.itemView.alpha = if (row.enabled == false) 0.5f else 1f
            h.itemView.setOnClickListener { onClick(row) }
        }

        private object Diff : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = a.key == b.key
            override fun areContentsTheSame(a: Row, b: Row) = a.same(b)
        }
    }
}
