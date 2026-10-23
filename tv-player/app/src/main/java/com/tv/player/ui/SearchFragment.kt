package com.tv.player.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.tv.player.R
import com.tv.player.core.Grouper
import com.tv.player.core.Hot
import com.tv.player.core.Search
import com.tv.player.core.Store
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SearchFragment : Fragment(R.layout.fragment_search) {
    private var job: Job? = null
    private lateinit var adapter: VodGroupAdapter
    private lateinit var query: EditText
    private lateinit var status: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var historyBox: View
    private lateinit var history: ChipGroup
    private lateinit var empty: View
    private lateinit var results: RecyclerView
    private lateinit var hotBox: View
    private lateinit var hotList: LinearLayout
    private var searching = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        query = view.findViewById(R.id.query)
        status = view.findViewById(R.id.status)
        progress = view.findViewById(R.id.progress)
        historyBox = view.findViewById(R.id.historyBox)
        history = view.findViewById(R.id.history)
        empty = view.findViewById(R.id.empty)
        results = view.findViewById(R.id.results)

        hotBox = view.findViewById(R.id.hot)
        hotList = view.findViewById(R.id.hotList)
        updateSubtitle()
        empty.findViewById<ImageView>(R.id.emptyIcon).setImageResource(R.drawable.ic_search)
        empty.findViewById<TextView>(R.id.emptyTitle).text = "输入片名开始搜索"
        empty.findViewById<TextView>(R.id.emptySub).text = "多个源里的同一部片子会合并成一张卡片，\n某条线路播放失败会自动换下一条"

        adapter = VodGroupAdapter(onClick = { PlayActivity.startVod(requireContext(), it) })
        results.layoutManager = GridLayoutManager(requireContext(), requireContext().gridSpan())
        results.adapter = adapter

        val clear = view.findViewById<View>(R.id.clear)
        query.doAfterTextChanged { clear.visibility = if (it.isNullOrEmpty()) View.GONE else View.VISIBLE }
        clear.setOnClickListener {
            query.text.clear()
            reset()
        }
        query.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search(query.text.toString())
                true
            } else {
                false
            }
        }
        view.findViewById<View>(R.id.searchIcon).setOnClickListener { search(query.text.toString()) }
        status.setOnClickListener {
            if (!searching && hotList.childCount == 0) {
                status.text = "正在加载热播榜…"
                loadHot(force = true)
            }
        }
        reset()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) updateSubtitle()
    }

    override fun onResume() {
        super.onResume()
        updateSubtitle()
    }

    private fun updateSubtitle() {
        if (view == null) return
        query.hint = "同时搜索 ${Store.sources().size} 个源的电影、剧集、综艺"
    }

    private fun reset() {
        job?.cancel()
        adapter.submitList(emptyList())
        progress.visibility = View.INVISIBLE
        status.text = ""
        searching = false
        syncIdle()
        showHistory()
        loadHot()
    }

    /** With no search running: the hot charts if there are any. The big empty-state picture is only for "no results". */
    private fun syncIdle() {
        hotBox.visibility = if (hotList.childCount > 0) View.VISIBLE else View.GONE
        empty.visibility = View.GONE
    }

    private fun loadHot(force: Boolean = false) {
        viewLifecycleOwner.lifecycleScope.launch {
            if (hotList.childCount == 0) Hot.cached()?.let { renderHot(it) }
            val fresh = Hot.refresh(force)
            if (fresh != null) {
                renderHot(fresh)
                if (!searching) status.text = ""
            } else if (hotList.childCount == 0 && !searching) {
                status.text = "热播榜没加载出来（可能没连上豆瓣），点这里重试，或直接搜索"
            }
        }
    }

    private fun renderHot(sections: List<Hot.Section>) {
        hotList.removeAllViews()
        for (section in sections) {
            val row = layoutInflater.inflate(R.layout.view_hot_section, hotList, false)
            row.findViewById<TextView>(R.id.sectionTitle).text = section.title
            val list = row.findViewById<RecyclerView>(R.id.sectionList)
            list.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            list.adapter = HotAdapter(::openHot).also { it.submitList(section.items) }
            hotList.addView(row)
        }
        if (!searching) syncIdle()
    }

    /** "第二季" style suffixes are dropped so every season shows up in the results. */
    private fun openHot(item: Hot.Item) {
        val keyword = item.title.replace(Regex("\\s*第[一二三四五六七八九十0-9]+季.*$"), "").ifBlank { item.title }
        query.setText(keyword)
        search(keyword)
    }

    private fun showHistory() {
        val items = Store.history()
        history.removeAllViews()
        for (kw in items) {
            history.addView(
                Chip(requireContext()).apply {
                    text = kw
                    textSize = 13f
                    chipMinHeight = requireContext().dp(30).toFloat()
                    setEnsureMinTouchTargetSize(false)
                    setOnClickListener {
                        query.setText(kw)
                        search(kw)
                    }
                },
            )
        }
        historyBox.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun search(raw: String) {
        val keyword = raw.trim()
        if (keyword.isEmpty()) return
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(query.windowToken, 0)
        Store.addHistory(keyword)
        historyBox.visibility = View.GONE

        val sources = Store.sources()
        if (sources.isEmpty()) {
            status.text = "没有可用的点播源，去“设置”里更新订阅"
            return
        }

        job?.cancel()
        val grouper = Grouper()
        adapter.submitList(emptyList())
        searching = true
        hotBox.visibility = View.GONE
        empty.visibility = View.GONE
        progress.visibility = View.VISIBLE
        status.text = "正在搜索 ${sources.size} 个源…"

        var done = 0
        var ok = 0
        job = viewLifecycleOwner.lifecycleScope.launch {
            Search.all(sources, keyword).collect { hit ->
                done++
                if (hit.error == null) ok++
                grouper.add(hit.items)
                adapter.submitList(grouper.snapshot(keyword))
                status.text = "已返回 $done/${sources.size} 个源 · ${grouper.size} 个结果"
            }
            progress.visibility = View.INVISIBLE
            status.text = "完成：$ok/${sources.size} 个源可用，共 ${grouper.size} 个结果"
            if (grouper.size == 0) {
                empty.findViewById<ImageView>(R.id.emptyIcon).setImageResource(R.drawable.ic_search)
                empty.findViewById<TextView>(R.id.emptyTitle).text = "没有找到「$keyword」"
                empty.findViewById<TextView>(R.id.emptySub).text = "换个关键词，或者少写几个字再试"
                empty.visibility = View.VISIBLE
            }
        }
    }
}
