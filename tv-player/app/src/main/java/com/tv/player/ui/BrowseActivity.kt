package com.tv.player.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.tv.player.R
import com.tv.player.core.Browse
import com.tv.player.core.Cms
import com.tv.player.core.Hot
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 分类筛选：豆瓣片单按电影 / 剧集、类型、地区、年代等筛选（见 Browse），滚到底自动加载下一页。
 * 点一部就回到发现页，在所有源里搜这个片名（和点热播榜一样）。
 */
class BrowseActivity : BaseActivity() {
    companion object {
        fun start(context: Context) = context.startActivity(Intent(context, BrowseActivity::class.java))
    }

    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private lateinit var grid: RecyclerView
    private val adapter = HotAdapter(::open, grid = true)
    private var kind = "tv"
    private val selected = LinkedHashMap<String, String>()
    private var sort = "T"
    private var categories: List<Browse.Category> = emptyList()
    private var sorts: List<Pair<String, String>> = emptyList()
    private var items: List<Hot.Item> = emptyList()
    private var total = 0
    private var nextStart = 0
    private var end = false
    private var loading: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)
        if (isTv()) findViewById<View>(R.id.browseContent).setPadding(dp(48), dp(27), dp(48), 0)
        rows = findViewById(R.id.filterRows)
        status = findViewById(R.id.browseStatus)
        grid = findViewById(R.id.browseGrid)
        grid.layoutManager = GridLayoutManager(this, gridSpan())
        grid.adapter = adapter
        grid.isFocusable = false
        if (isTv()) grid.setPadding(dp(12), dp(16), dp(12), dp(16)) // 聚焦放大的海报不被裁
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val last = (rv.layoutManager as GridLayoutManager).findLastVisibleItemPosition()
                if (last >= items.size - gridSpan() * 2) more()
            }
        })
        // 电视上焦点走到最后几张海报时也接着加载（遥控器移动焦点不一定触发滚动回调）
        grid.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            val pos = now?.let { grid.findContainingViewHolder(it)?.bindingAdapterPosition } ?: return@addOnGlobalFocusChangeListener
            if (pos >= items.size - gridSpan() * 2) more()
        }
        buildRows()
        reload()
    }

    /** 一行一组筛选：电影 / 剧集，接口给的各组分类，排序。都是单选的标签，左右键横着走。 */
    private fun buildRows() {
        // 重建会删掉带焦点的标签：记下是哪一行，建好后把焦点放回那一行选中的标签上
        val focusedRow = (0 until rows.childCount).indexOfFirst { rows.getChildAt(it).hasFocus() }
        rows.removeAllViews()
        addRow(listOf("tv" to "剧集", "movie" to "电影"), kind) {
            kind = it
            selected.clear()
            categories = emptyList()
            sort = "T"
            buildRows()
            reload()
        }
        categories.forEach { c ->
            addRow(c.options.map { it to it }, selected[c.name] ?: c.options.first()) {
                selected[c.name] = it
                reload()
            }
        }
        if (sorts.isNotEmpty()) addRow(sorts, sort) {
            sort = it
            reload()
        }
        if (focusedRow >= 0) {
            val group = (rows.getChildAt(focusedRow.coerceAtMost(rows.childCount - 1)) as? HorizontalScrollView)?.getChildAt(0) as? ChipGroup
            group?.findViewById<View>(group.checkedChipId)?.requestFocus()
        }
    }

    private fun addRow(options: List<Pair<String, String>>, checked: String, onPick: (String) -> Unit) {
        val group = ChipGroup(this).apply {
            isSingleLine = true
            isSingleSelection = true
            isSelectionRequired = true
            chipSpacingVertical = 0
        }
        options.forEach { (value, label) ->
            group.addView(Chip(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 13f
                isCheckable = true
                isChecked = value == checked
                chipMinHeight = dp(34).toFloat()
                setOnClickListener { if (isChecked) onPick(value) }
            })
        }
        rows.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(group)
        })
    }

    private fun reload() {
        // 剧集默认看电视剧（不选「形式」时接口给的是推荐流，只有几条）
        if (kind == "tv" && selected[Browse.FORM].isNullOrEmpty()) selected[Browse.FORM] = "电视剧"
        loading?.cancel()
        items = emptyList()
        total = 0
        nextStart = 0
        end = false
        adapter.submitList(emptyList())
        load(0)
    }

    private fun more() {
        if (loading?.isActive == true || items.isEmpty() || end || nextStart >= total) return
        load(nextStart)
    }

    private fun load(start: Int) {
        status.text = "正在加载…"
        loading = lifecycleScope.launch {
            val page = Browse.page(kind, selected, sort, start)
            if (page == null) {
                status.text = if (start == 0) "没加载出来（可能没连上豆瓣），换个条件或稍后再试" else "下一页没加载出来，往下滑重试"
                return@launch
            }
            // 第一页带回这一类能选的分类和排序：第一次拿到时把筛选行补齐
            if (categories.isEmpty() && page.categories.isNotEmpty() || sorts.isEmpty() && page.sorts.isNotEmpty()) {
                categories = page.categories
                sorts = page.sorts
                buildRows()
            }
            total = page.total
            nextStart = start + page.raw
            end = page.raw == 0 // 空页：到底了，别再一滚就请求
            items = (items + page.items).distinctBy { it.title }
            android.util.Log.d("TVP", "browse $kind $selected from $start: ${page.items.size} items, ${items.size}/$total shown")
            adapter.submitList(items)
            status.text = if (items.isEmpty()) "这个条件下没有片子" else "共 ${total} 部，点一部在所有源里搜索"
        }
    }

    private fun open(item: Hot.Item) {
        val keyword = Cms.cleanTitle(item.title).replace(Regex("\\s*第[一二三四五六七八九十0-9]+季.*$"), "").ifBlank { item.title }
        startActivity(MainActivity.searchIntent(this, keyword))
    }
}
