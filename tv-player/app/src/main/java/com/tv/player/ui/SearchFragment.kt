package com.tv.player.ui

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.tabs.TabLayout
import com.tv.player.R
import com.tv.player.core.Grouper
import com.tv.player.core.Hot
import com.tv.player.core.Http
import com.tv.player.core.Library
import com.tv.player.core.Search
import com.tv.player.core.ResultFilter
import com.tv.player.core.Store
import coil.load
import coil.transform.RoundedCornersTransformation
import com.tv.player.data.VodGroup
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
    private lateinit var hotTabs: TabLayout
    private lateinit var hotGrid: RecyclerView
    private lateinit var hotRows: RowScrollView
    private lateinit var hotRowsList: LinearLayout
    private lateinit var hotAdapter: HotAdapter
    private var hotSections: List<Hot.Section> = emptyList()
    private var searching = false
    private lateinit var filterBox: View
    private lateinit var filterChips: ChipGroup
    private val filter = ResultFilter()
    private var raw: List<VodGroup> = emptyList()
    private var keyword = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        query = view.findViewById(R.id.query)
        status = view.findViewById(R.id.status)
        progress = view.findViewById(R.id.progress)
        historyBox = view.findViewById(R.id.historyBox)
        history = view.findViewById(R.id.history)
        empty = view.findViewById(R.id.empty)
        results = view.findViewById(R.id.results)

        hotBox = view.findViewById(R.id.hot)
        filterBox = view.findViewById(R.id.filterBox)
        filterChips = view.findViewById(R.id.filters)
        hotTabs = view.findViewById(R.id.hotTabs)
        hotGrid = view.findViewById(R.id.hotGrid)
        hotRows = view.findViewById(R.id.hotRows)
        if (requireContext().isTv()) hotRows.titleGap = requireContext().dp(20)
        hotRowsList = view.findViewById(R.id.hotRowsList)
        hotAdapter = HotAdapter(::openHot, grid = true)
        hotGrid.layoutManager = GridLayoutManager(requireContext(), requireContext().gridSpan())
        hotGrid.adapter = hotAdapter
        hotTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                hotSections.getOrNull(tab.position)?.let {
                    hotAdapter.submitList(it.items) { hotGrid.scrollToPosition(0) }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        updateSubtitle()
        empty.findViewById<ImageView>(R.id.emptyIcon).setImageResource(R.drawable.ic_search)
        empty.findViewById<TextView>(R.id.emptyTitle).text = "输入片名开始搜索"
        empty.findViewById<TextView>(R.id.emptySub).text = "多个源里的同一部片子会合并成一张卡片，\n某条线路播放失败会自动换下一条"

        adapter = VodGroupAdapter(onClick = { PlayActivity.startVod(requireContext(), it) })
        results.layoutManager = GridLayoutManager(requireContext(), requireContext().gridSpan())
        results.adapter = adapter

        // An empty status line must not take up a row of height.
        status.doAfterTextChanged { status.visibility = if (it.isNullOrEmpty()) View.GONE else View.VISIBLE }

        view.findViewById<View>(R.id.phoneInput).apply {
            visibility = if (requireContext().isTv()) View.VISIBLE else View.GONE
            setOnClickListener { PhonePush.show(requireActivity() as androidx.appcompat.app.AppCompatActivity) { runSearch(it) } }
        }
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
            if (!searching && hotSections.isEmpty()) {
                status.text = "正在加载热播榜…"
                loadHot(force = true)
            }
        }
        reset()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            updateSubtitle()
            if (!searching) {
                if (hotSections.isNotEmpty()) renderHot(hotSections) // the layout may have been switched in settings
                loadHot()
            }
        }
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
        progress.visibility = View.GONE
        status.text = ""
        searching = false
        // Back to the charts: the filters belonged to the search that was cleared.
        raw = emptyList()
        filter.reset()
        filterBox.visibility = View.GONE
        syncIdle()
        showHistory()
        loadHot()
    }

    /** With no search running: the hot charts if there are any. The big empty-state picture is only for "no results". */
    private fun syncIdle() {
        hotBox.visibility = if (hotSections.isNotEmpty()) View.VISIBLE else View.GONE
        empty.visibility = View.GONE
    }

    private fun loadHot(force: Boolean = false) {
        viewLifecycleOwner.lifecycleScope.launch {
            if (hotSections.isEmpty()) Hot.cached()?.let { renderHot(it) }
            val fresh = Hot.refresh(force)
            if (fresh != null) {
                renderHot(fresh)
                if (!searching) status.text = ""
            } else if (hotSections.isEmpty() && !searching) {
                status.text = "热播榜没加载出来（可能没连上豆瓣），点这里重试，或直接搜索"
            }
        }
    }

    private fun renderHot(sections: List<Hot.Section>) {
        // TV home is always the rows: a banner, "继续观看", then one horizontal row per chart.
        val rows = requireContext().isTv() || Store.hotLayout() == "rows"
        hotTabs.visibility = if (rows) View.GONE else View.VISIBLE
        hotGrid.visibility = if (rows) View.GONE else View.VISIBLE
        hotRows.visibility = if (rows) View.VISIBLE else View.GONE
        if (rows) {
            hotSections = sections
            hotRowsList.removeAllViews()
            val tv = requireContext().isTv()
            if (tv) addHero(sections.firstOrNull()?.items?.firstOrNull(), sections.firstOrNull()?.title?.removePrefix("热播").orEmpty())
            addContinueRow()
            addUpdatesRow()
            for (section in sections) {
                addRow(section.title, section.items, ::openHot, if (tv) section.title.removePrefix("热播") else null)
            }
            // Titles the charts rate highest, across all of them: a row of its own, so it is easy to pick something good.
            val best = sections.flatMap { it.items }.filter { it.rating > 0 }.distinctBy { it.title }.sortedByDescending { it.rating }.take(12)
            if (best.size >= 4) addRow("高分推荐", best, ::openHot, if (tv) "高分推荐" else null)
            if (!searching) syncIdle()
            return
        }
        // Keep the tab the user was on (matched by title) when the list refreshes.
        val keep = hotSections.getOrNull(hotTabs.selectedTabPosition)?.title
        hotSections = sections
        hotTabs.removeAllTabs()
        sections.forEach { hotTabs.addTab(hotTabs.newTab().setText(it.title.removePrefix("热播").removePrefix("最近更新 · ")), false) }
        val at = sections.indexOfFirst { it.title == keep }.coerceAtLeast(0)
        hotTabs.getTabAt(at)?.select()
        hotAdapter.submitList(sections[at].items)
        if (!searching) syncIdle()
    }

    private var hero: View? = null
    private var heroItem: Hot.Item? = null
    private var heroTask: Runnable? = null

    /** TV home: the banner above the rows. It starts on the first hot title and then follows the remote's focus. */
    private fun addHero(first: Hot.Item?, firstLabel: String) {
        if (first == null) return
        // Fixed above the rows (not inside the scrolling list), so it stays in view while the focus moves through the rows.
        val box = hotBox as ViewGroup
        hero?.let(box::removeView)
        val view = layoutInflater.inflate(R.layout.view_tv_hero, box, false)
        hero = view
        heroItem = null
        view.findViewById<View>(R.id.heroPlay).setOnClickListener { heroItem?.let(::openHot) }
        box.addView(view, 0)
        showHero(first, firstLabel)
    }

    /** The focus moved to [item]: show it in the banner, after a short pause so a quick scroll does not flicker it. */
    private fun focusHero(item: Hot.Item, label: String) {
        val view = hero ?: return
        heroTask?.let(view::removeCallbacks)
        heroTask = Runnable { showHero(item, label) }.also { view.postDelayed(it, 150) }
    }

    private fun showHero(item: Hot.Item, label: String) {
        val view = hero ?: return
        if (heroItem?.title == item.title) return
        heroItem = item
        view.findViewById<View>(R.id.heroText).apply {
            alpha = 0f
            animate().alpha(1f).setDuration(160).start()
        }
        view.findViewById<TextView>(R.id.heroTitle).text = item.title
        view.findViewById<TextView>(R.id.heroMeta).text =
            listOf(label, if (item.rating > 0) "★ %.1f".format(java.util.Locale.ROOT, item.rating) else "", item.sub.replace(" / ", "  ·  "))
                .filter { it.isNotEmpty() }.joinToString("  ·  ")
        val pic = item.pic.takeIf { it.isNotEmpty() }
        val backdrop = view.findViewById<ImageView>(R.id.heroBackdrop)
        // The poster, enlarged and blurred, is the backdrop: it has no sharp edges to show how small the source is.
        if (Build.VERSION.SDK_INT >= 31) backdrop.setRenderEffect(RenderEffect.createBlurEffect(40f, 40f, Shader.TileMode.CLAMP))
        for ((id, round) in listOf(R.id.heroBackdrop to 0, R.id.heroPoster to 10)) {
            view.findViewById<ImageView>(id).load(pic) {
                addHeader("User-Agent", Http.UA)
                if ("doubanio.com" in item.pic) addHeader("Referer", Hot.IMAGE_REFERER)
                crossfade(true)
                if (round > 0) transformations(RoundedCornersTransformation(requireContext().dp(round).toFloat()))
            }
        }
    }

    /** One titled horizontal row of posters. [heroLabel] non-null: on TV the banner follows the focus in this row. */
    private fun addRow(title: String, items: List<Hot.Item>, onClick: (Hot.Item) -> Unit, heroLabel: String?) {
        val row = layoutInflater.inflate(R.layout.view_hot_section, hotRowsList, false)
        row.findViewById<TextView>(R.id.sectionTitle).apply {
            text = title
            if (requireContext().isTv()) textSize = 22f
        }
        val list = row.findViewById<RecyclerView>(R.id.sectionList)
        list.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        list.adapter = HotAdapter(onClick, onFocus = heroLabel?.let { label -> { item: Hot.Item -> focusHero(item, label) } }).also { it.submitList(items) }
        hotRowsList.addView(row)
    }

    /** What the user was watching, first, so a title can be picked up where it was left. */
    private fun addContinueRow() {
        val watching = Library.history(12)
        if (watching.isEmpty()) return
        val items = watching.map { Hot.Item(it.title, it.pic, 0.0, if (it.epName.isNotEmpty()) "看到 ${it.epName}" else "") }
        addRow("继续观看", items, { picked ->
            watching.firstOrNull { it.title == picked.title }?.let { PlayActivity.startVod(requireContext(), it.toGroup(withProgress = true)) }
        }, if (requireContext().isTv()) "继续观看" else null)
    }

    /** Favorites that got new episodes since they were last opened. */
    private fun addUpdatesRow() {
        val updated = Library.favorites().filter { it.fresh > 0 }
        if (updated.isEmpty()) return
        val items = updated.map { Hot.Item(it.title, it.pic, 0.0, "更新 +${it.fresh} 集") }
        addRow("追剧更新", items, { picked ->
            updated.firstOrNull { it.title == picked.title }?.let { PlayActivity.startVod(requireContext(), it.toFavoriteGroup()) }
        }, if (requireContext().isTv()) "追剧更新" else null)
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
                    chipMinHeight = requireContext().dp(34).toFloat()
                    setOnClickListener {
                        query.setText(kw)
                        search(kw)
                    }
                    // The small cross deletes one entry; long-press does the same (the way to do it with a remote).
                    isCloseIconVisible = true
                    closeIconSize = requireContext().dp(16).toFloat()
                    setOnCloseIconClickListener {
                        Store.removeHistory(kw)
                        showHistory()
                    }
                    setOnLongClickListener {
                        Store.removeHistory(kw)
                        showHistory()
                        true
                    }
                },
            )
        }
        if (items.isNotEmpty()) {
            history.addView(
                Chip(requireContext()).apply {
                    text = "清空"
                    textSize = 13f
                    chipMinHeight = requireContext().dp(34).toFloat()
                    setChipIconResource(R.drawable.ic_delete)
                    isChipIconVisible = true
                    chipIconSize = requireContext().dp(16).toFloat()
                    setOnClickListener {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("清空全部搜索历史？")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("清空") { _, _ ->
                                Store.clearHistory()
                                showHistory()
                            }
                            .show()
                    }
                },
            )
        }
        historyBox.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Fills the box and searches, for keywords that arrive from the phone. */
    fun runSearch(keyword: String) {
        if (view == null) return
        query.setText(keyword)
        search(keyword)
    }

    /** The merged results through the current sort / filter, plus the row of filter chips. */
    private fun showResults() {
        val shown = filter.apply(raw)
        adapter.submitList(shown)
        buildFilters(shown.size)
    }

    private fun buildFilters(shown: Int) {
        filterChips.removeAllViews()
        if (raw.size < 2 && !filter.active) {
            filterBox.visibility = View.GONE
            return
        }
        fun chip(text: String, active: Boolean, onClick: () -> Unit) = Chip(requireContext()).apply {
            this.text = text
            textSize = 13f
            chipMinHeight = requireContext().dp(34).toFloat()
            isCheckable = true
            isChecked = active
            setOnClickListener {
                isChecked = active // the dialog decides; do not flip by itself
                onClick()
            }
            filterChips.addView(this)
        }
        fun pick(title: String, options: List<String>, checked: Int, onPick: (Int) -> Unit) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), checked) { d, i ->
                    onPick(i)
                    d.dismiss()
                    showResults()
                }
                .show()
        }
        chip("排序：${ResultFilter.SORTS[filter.sort]}", filter.sort != 0) {
            pick("排序", ResultFilter.SORTS, filter.sort) { filter.sort = it }
        }
        val years = filter.years(raw)
        if (years.isNotEmpty()) chip(if (filter.year.isEmpty()) "年份" else "年份：${filter.year}", filter.year.isNotEmpty()) {
            val options = listOf("全部") + years
            pick("年份", options, options.indexOf(filter.year).coerceAtLeast(0)) { filter.year = if (it == 0) "" else options[it] }
        }
        val kinds = filter.kinds(raw)
        if (kinds.size >= 2 || filter.kind.isNotEmpty()) chip(if (filter.kind.isEmpty()) "类型" else "类型：${filter.kind}", filter.kind.isNotEmpty()) {
            val options = listOf("全部") + kinds
            pick("类型", options, options.indexOf(filter.kind).coerceAtLeast(0)) { filter.kind = if (it == 0) "" else options[it] }
        }
        chip(if (filter.quality == 0) "清晰度" else "清晰度：${ResultFilter.QUALITIES[filter.quality]}", filter.quality != 0) {
            pick("清晰度（按片源备注判断）", ResultFilter.QUALITIES, filter.quality) { filter.quality = it }
        }
        val sources = filter.sources(raw)
        if (sources.size >= 2 || filter.source.isNotEmpty()) chip(if (filter.source.isEmpty()) "来源" else "来源：${filter.source}", filter.source.isNotEmpty()) {
            val options = listOf("全部") + sources
            pick("来源", options, options.indexOf(filter.source).coerceAtLeast(0)) { filter.source = if (it == 0) "" else options[it] }
        }
        if (filter.active) {
            chip("重置（${shown}/${raw.size}）", false) {
                filter.reset()
                showResults()
            }
        }
        filterBox.visibility = View.VISIBLE
    }

    private fun search(text: String) {
        val keyword = text.trim()
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
        this.keyword = keyword
        filter.reset()
        raw = emptyList()
        filterBox.visibility = View.GONE
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
                raw = grouper.snapshot(keyword)
                showResults()
                status.text = "已返回 $done/${sources.size} 个源 · ${grouper.size} 个结果"
            }
            progress.visibility = View.GONE
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
