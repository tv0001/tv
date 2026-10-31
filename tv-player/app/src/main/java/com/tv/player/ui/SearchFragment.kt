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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.tabs.TabLayout
import com.tv.player.R
import com.tv.player.core.Ai
import com.tv.player.core.Cms
import com.tv.player.core.Grouper
import com.tv.player.core.Hot
import com.tv.player.core.Library
import com.tv.player.core.ResultFilter
import com.tv.player.core.Search
import com.tv.player.core.Smart
import com.tv.player.core.Store
import com.tv.player.core.Suggest
import com.tv.player.data.VodGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SearchFragment : Fragment(R.layout.fragment_search) {
    private var job: Job? = null
    private lateinit var adapter: VodGroupAdapter
    private lateinit var query: EditText
    private lateinit var status: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var historyBox: View
    private lateinit var hotExtra: LinearLayout
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

    // 搜索联想和电视上的首字母键盘
    private lateinit var suggestBox: View
    private lateinit var suggestChips: ChipGroup
    private lateinit var keyboard: android.widget.GridLayout
    private var suggestJob: Job? = null
    private var suggestions: List<String> = emptyList()
    private var suggestedFor = "" // suggestions 是按哪段文字给的
    private var quietFocus = false // 程序把焦点挪回搜索框时，不要因此弹出键盘
    private var pendingSearch: String? = null // 页面还没建好时收到的搜索（从分类筛选页回来）

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        query = view.findViewById(R.id.query)
        suggestBox = view.findViewById(R.id.suggestBox)
        suggestChips = view.findViewById(R.id.suggest)
        keyboard = view.findViewById(R.id.keyboard)
        status = view.findViewById(R.id.status)
        progress = view.findViewById(R.id.progress)
        historyBox = view.findViewById(R.id.historyBox)
        hotExtra = view.findViewById(R.id.hotExtra)
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
        // RecyclerView makes itself focusable in its constructor, over the layout's focusable="false". The empty
        // results list sits under the charts and would take the remote's focus (Right from the navigation, Up from
        // the first row) while showing nothing. Only the cards inside should ever be focused.
        results.isFocusable = false
        hotGrid.isFocusable = false
        // TV: a focused card grows 1.1x past its cell; the grid keeps that much room so the top row is not cut by the tabs.
        if (requireContext().isTv()) hotGrid.setPadding(requireContext().dp(12), requireContext().dp(16), requireContext().dp(12), requireContext().dp(16))

        // An empty status line must not take up a row of height.
        status.doAfterTextChanged { status.visibility = if (it.isNullOrEmpty()) View.GONE else View.VISIBLE }

        view.findViewById<View>(R.id.phoneInput).apply {
            visibility = if (requireContext().isTv()) View.VISIBLE else View.GONE
            setOnClickListener { PhonePush.show(requireActivity() as androidx.appcompat.app.AppCompatActivity) { runSearch(it) } }
        }
        view.findViewById<View>(R.id.browse).setOnClickListener { BrowseActivity.start(requireContext()) }
        val clear = view.findViewById<View>(R.id.clear)
        query.doAfterTextChanged {
            clear.visibility = if (it.isNullOrEmpty()) View.GONE else View.VISIBLE
            updateSuggestions(it?.toString().orEmpty())
        }
        if (requireContext().isTv()) {
            // 电视上点搜索框弹出下面的首字母键盘，不弹系统输入法（键盘上有「系统键盘」可以切过去输中文）
            query.showSoftInputOnFocus = false
            query.setOnClickListener { showKeyboard(true) }
            query.setOnFocusChangeListener { _, has -> if (has && !quietFocus) showKeyboard(true) }
            buildKeyboard()
        }
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
        if (requireContext().isTv()) view.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ ->
            syncHistory()
            syncKeyboard()
        }
        reset()
        pendingSearch?.let {
            pendingSearch = null
            runSearch(it)
        }
    }

    /**
     * 电视上按返回键时先清空搜索框（和点清除按钮一样），框里没字才轮到离开应用。由 MainActivity 的返回处理调用；
     * 清空了返回 true。手机上不处理，始终返回 false。
     */
    fun clearOnBack(): Boolean {
        if (view == null || !requireContext().isTv()) return false
        if (query.text.isEmpty()) {
            // 没字但首字母键盘开着：先收起键盘
            if (keyboard.visibility != View.VISIBLE) return false
            showKeyboard(false)
            return true
        }
        query.text.clear()
        reset()
        return true
    }

    // ---- 搜索联想 ------------------------------------------------------------------------------------------------

    /** 本地的片名：热播榜、观看记录、收藏、搜索历史。 */
    private fun suggestPool(): List<String> =
        hotSections.flatMap { s -> s.items.map { it.title } } + Library.history(100).map { it.title } + Library.favorites().map { it.title } + Store.history()

    /** 输入停 0.25 秒后更新联想：先给本地的，中文再补豆瓣的。搜索进行中不打扰。 */
    private fun updateSuggestions(text: String) {
        suggestJob?.cancel()
        if (text.isBlank() || searching && text == keyword) {
            showSuggestions(emptyList())
            return
        }
        suggestJob = viewLifecycleOwner.lifecycleScope.launch {
            kotlinx.coroutines.delay(250)
            val local = withContext(Dispatchers.Default) { Suggest.local(text, suggestPool()) }
            showSuggestions(local)
            val remote = runCatching { Suggest.douban(text) }.getOrDefault(emptyList())
            if (remote.isNotEmpty()) showSuggestions((local + remote).distinct().filter { it != text.trim() }.take(10))
        }
    }

    private fun showSuggestions(list: List<String>, forText: String = query.text.toString()) {
        if (view == null) return
        suggestions = list
        suggestedFor = forText
        suggestChips.removeAllViews()
        list.forEach { title ->
            suggestChips.addView(Chip(requireContext()).apply {
                text = title
                textSize = 13f
                chipMinHeight = requireContext().dp(34).toFloat()
                setOnClickListener {
                    query.setText(title)
                    search(title)
                }
            })
        }
        suggestBox.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
    }

    // ---- 电视上的首字母键盘 --------------------------------------------------------------------------------------

    /** A–Z、0–9，加上删除、清空、系统键盘、搜索，一排 10 个。 */
    private fun buildKeyboard() {
        val ctx = requireContext()
        val keys = ('A'..'Z').map { it.toString() } + ('0'..'9').map { it.toString() } + listOf("删除", "清空", "系统键盘", "搜索")
        keyboard.removeAllViews()
        keys.forEach { k ->
            val button = layoutInflater.inflate(if (k == "搜索") R.layout.item_key_primary else R.layout.item_key, keyboard, false) as com.google.android.material.button.MaterialButton
            button.text = k
            if (k.length > 1) button.textSize = 13f
            button.setOnClickListener { pressKey(k) }
            // 行按居中对齐：GridLayout 默认按文字基线对齐，字号小一号的键会比同排的低一截
            val params = android.widget.GridLayout.LayoutParams(
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, android.widget.GridLayout.CENTER),
                android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f),
            ).apply {
                width = 0
                height = ctx.dp(44)
                setMargins(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
            }
            keyboard.addView(button, params)
        }
    }

    private fun pressKey(k: String) {
        val text = query.text
        when (k) {
            "删除" -> if (text.isNotEmpty()) text.delete(text.length - 1, text.length)
            "清空" -> {
                text.clear()
                reset()
            }
            "系统键盘" -> {
                query.requestFocus()
                (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(query, InputMethodManager.SHOW_FORCED)
            }
            "搜索" -> {
                // 打的是首字母时，搜第一个联想出来的片名；但打的正好是某个片名（如「2012」），或联想还没跟上这段文字，就搜原文
                val typed = text.toString().trim()
                val exact = suggestPool().any { it.equals(typed, ignoreCase = true) }
                val fresh = suggestedFor.trim() == typed && suggestions.isNotEmpty()
                val target = if (typed.all { it.code < 128 } && fresh && !exact) suggestions.first() else typed
                if (target.isNotEmpty()) {
                    query.setText(target)
                    search(target)
                }
            }
            else -> text.append(k.lowercase())
        }
    }

    private fun showKeyboard(show: Boolean) {
        if (view == null || !requireContext().isTv()) return
        if (!show) keepFocusInBox()
        keyboard.visibility = if (show) View.VISIBLE else View.GONE
    }

    /** 键盘或联想要藏起来时，焦点在它们上面就先挪回搜索框（不然会落到左边的导航栏上）。 */
    private fun keepFocusInBox() {
        if (keyboard.hasFocus() || suggestBox.hasFocus()) {
            quietFocus = true
            query.requestFocus()
            quietFocus = false
        }
    }

    /** 焦点离开搜索框、联想和键盘这一片，键盘就收起。 */
    private fun syncKeyboard() {
        if (view == null || keyboard.visibility != View.VISIBLE) return
        val inside = (query.parent as View).findFocus() != null || keyboard.findFocus() != null || suggestBox.findFocus() != null
        if (!inside) keyboard.visibility = View.GONE
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
        // Back from the player: what was just watched belongs in the compact row (phones, tabs layout).
        if (hotSections.isNotEmpty() && !searching && Store.hotLayout() != "rows") fillExtra()
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
        showSuggestions(emptyList())
        // The layout setting may have changed while the results were up; redraw from what is loaded first, in case
        // the refresh below cannot reach the network and returns nothing.
        if (hotSections.isNotEmpty()) renderHot(hotSections)
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
        // "tabs": one chart per top tab as a grid; "rows": "继续观看", then one horizontal row per chart. TV included.
        val rows = Store.hotLayout() == "rows"
        hotTabs.visibility = if (rows) View.GONE else View.VISIBLE
        hotGrid.visibility = if (rows) View.GONE else View.VISIBLE
        hotRows.visibility = if (rows) View.VISIBLE else View.GONE
        if (rows) {
            hotSections = sections
            hotRowsList.removeAllViews()
            hotExtra.removeAllViews()
            hotExtra.visibility = View.GONE
            addContinueRow()
            addUpdatesRow()
            for (section in sections) addRow(section.title, section.items, ::openHot)
            // Titles the charts rate highest, across all of them: a row of its own, so it is easy to pick something good.
            val best = sections.flatMap { it.items }.filter { it.rating > 0 }.distinctBy { it.title }.sortedByDescending { it.rating }.take(12)
            if (best.size >= 4) addRow("高分推荐", best, ::openHot)
            if (!searching) syncIdle()
            return
        }
        fillExtra()
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

    /** One titled horizontal row of posters. */
    private fun addRow(title: String, items: List<Hot.Item>, onClick: (Hot.Item) -> Unit) {
        val row = layoutInflater.inflate(R.layout.view_hot_section, hotRowsList, false)
        row.findViewById<TextView>(R.id.sectionTitle).apply {
            text = title
            if (requireContext().isTv()) textSize = 18f
        }
        val list = row.findViewById<RecyclerView>(R.id.sectionList)
        list.isFocusable = false // see the results list above: only the cards take the focus
        list.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        list.adapter = HotAdapter(onClick).also { it.submitList(items) }
        hotRowsList.addView(row)
    }

    /**
     * Phones and tablets (tabs layout): updated favorites first, then what was being watched, as one row of small posters
     * above the tabs. Not on TV: there a row of cards is as tall as a grid row, and the grid under it would not have room
     * for even one full row. The TV home keeps continue-watching in the rows layout and on the "我的" page.
     */
    private fun fillExtra() {
        hotExtra.removeAllViews()
        hotExtra.visibility = View.GONE
        val ctx = requireContext()
        if (ctx.isTv()) return
        val updated = Library.favorites().filter { it.fresh > 0 }
        val watching = Library.history(12).filter { h -> updated.none { it.key == h.key } }
        val groups = updated.map { it.toFavoriteGroup() } + watching.map { it.toGroup(withProgress = true) }
        if (groups.isEmpty()) return
        val shown = groups.take(10)
        val items = shown.map { Hot.Item(it.title, it.pic, 0.0, it.remarks.substringBefore(" · ")) }
        val row = layoutInflater.inflate(R.layout.view_hot_section, hotExtra, false)
        row.findViewById<TextView>(R.id.sectionTitle).text = if (updated.isEmpty()) "继续观看" else "继续观看 · 追剧更新"
        val list = row.findViewById<RecyclerView>(R.id.sectionList)
        list.isFocusable = false
        list.layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
        // The shared row has room for a TV card to grow; a phone row does not need it.
        list.setPadding(ctx.dp(8), ctx.dp(2), ctx.dp(8), ctx.dp(2))
        list.adapter = HotAdapter({ picked -> shown.firstOrNull { it.title == picked.title }?.let { PlayActivity.startVod(ctx, it) } }, widthDp = 76).also { it.submitList(items) }
        hotExtra.addView(row)
        hotExtra.visibility = View.VISIBLE
    }

    /** What the user was watching, first, so a title can be picked up where it was left. */
    private fun addContinueRow() {
        val watching = Library.history(12)
        if (watching.isEmpty()) return
        val items = watching.map { Hot.Item(it.title, it.pic, 0.0, if (it.epName.isNotEmpty()) "看到 ${it.epName}" else "") }
        addRow("继续观看", items) { picked ->
            watching.firstOrNull { it.title == picked.title }?.let { PlayActivity.startVod(requireContext(), it.toGroup(withProgress = true)) }
        }
    }

    /** Favorites that got new episodes since they were last opened. */
    private fun addUpdatesRow() {
        val updated = Library.favorites().filter { it.fresh > 0 }
        if (updated.isEmpty()) return
        val items = updated.map { Hot.Item(it.title, it.pic, 0.0, "更新 +${it.fresh} 集") }
        addRow("追剧更新", items) { picked ->
            updated.firstOrNull { it.title == picked.title }?.let { PlayActivity.startVod(requireContext(), it.toFavoriteGroup()) }
        }
    }

    /** "第二季" style suffixes are dropped so every season shows up in the results. */
    private fun openHot(item: Hot.Item) {
        val keyword = Cms.cleanTitle(item.title).replace(Regex("\\s*第[一二三四五六七八九十0-9]+季.*$"), "").ifBlank { item.title }
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
        syncHistory()
    }

    /**
     * TV: the history chips would sit between the search box and the rows all the time; they show only while the
     * search box or one of them has the focus. Elsewhere they show whenever there is any history.
     */
    private fun syncHistory() {
        if (view == null) return
        val any = history.childCount > 0
        val wanted = !requireContext().isTv() || searching || (query.parent as View).findFocus() != null || historyBox.findFocus() != null
        historyBox.visibility = if (any && wanted) View.VISIBLE else View.GONE
    }

    /** Fills the box and searches, for keywords that arrive from the phone. */
    fun runSearch(keyword: String) {
        if (view == null) {
            pendingSearch = keyword
            return
        }
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
        val keyword = Search.clean(text)
        if (keyword.isEmpty()) return
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(query.windowToken, 0)
        Store.addHistory(keyword)
        historyBox.visibility = View.GONE
        suggestJob?.cancel()
        keepFocusInBox()
        suggestBox.visibility = View.GONE
        keyboard.visibility = View.GONE

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

        job = viewLifecycleOwner.lifecycleScope.launch {
            // A sentence ("想看一部女主重生的短剧") is taken apart; a plain title is searched as typed.
            val plan = Smart.parse(keyword)
            // Titles that rank first in the list besides the typed text: what a retry fell back to, or what the AI suggested.
            val also = ArrayList<String>().apply { if (plan.query != keyword) add(plan.query) }
            var okEver = 0 // the most sources that answered in any one round
            var firstRound = true
            var how = ""
            val tried = HashSet<String>()

            /** One search round over every source; the results join the merged list as the sources answer. */
            suspend fun round(queries: List<String>, note: String, ms: Long, track: Boolean = firstRound) {
                var done = 0
                var ok = 0
                tried += queries
                status.text = "$note${sources.size} 个源…"
                // Only the first round counts towards a source's cool-down: one user search is one strike at most.
                val merged = queries.map { Search.all(sources, it, perSourceMs = ms, trackHealth = track) }.merge()
                val total = sources.size * queries.size
                merged.collect { hit ->
                    done++
                    if (hit.error == null) ok++
                    grouper.add(hit.items)
                    raw = grouper.snapshot(keyword, also.toList(), plan.kind, plan.year)
                    showResults()
                    status.text = "${note}已返回 $done/$total 个源 · ${grouper.size} 个结果"
                }
                okEver = maxOf(okEver, ok / queries.size.coerceAtLeast(1))
                if (track) firstRound = false
            }

            /** A shorter or corrected query: its title joins the ranking while it runs, and leaves again if it found nothing. */
            suspend fun retry(q: String, note: String, ms: Long = 6_000): Boolean {
                also += q
                round(listOf(q), note, ms)
                if (grouper.size == 0) also.remove(q)
                return grouper.size > 0
            }

            // 0. A sentence that was taken apart is first searched as typed, in case it is really a title that merely
            //    looks like one ("搜索者", "回到1980年的夏天"); found, it is left alone.
            var found = false
            if (plan.query != keyword) {
                // The 5 s limit is short for a slow source: that is no reason to count it as failed.
                round(listOf(keyword), "", 5_000, track = false)
                found = grouper.size > 0
                if (found) also.clear()
            }

            // 1. The whole (taken apart) keyword, then shorter ones (no season, no "全集", the first word).
            if (!found) {
                val tries = (listOf(plan.query) + Search.relaxed(plan.query, withPrefix = false) + plan.extras).distinct()
                    // A single character left over from taking a title apart ("者") would match everything that has it.
                    .filter { it !in tried && (it == keyword || Search.canon(it).length >= 2) }
                for ((n, q) in tries.withIndex()) {
                    val note = if (n == 0 && plan.query == keyword) "" else "没有找到「$keyword」，改搜「$q」 · "
                    if (n == 0) {
                        round(listOf(q), note, 10_000)
                        found = grouper.size > 0
                    } else {
                        found = retry(q, note)
                    }
                    if (found) {
                        if (q != keyword) how = "按「$q」搜到"
                        break
                    }
                    // Every source failing means the network, not the keyword: shorter queries would only fail again.
                    if (okEver == 0) break
                }
            }

            // 2. A typo: the closest title the user has watched, favorited or seen on the charts.
            if (grouper.size == 0 && okEver > 0) {
                val near = Smart.nearest(plan.query, knownTitles(Hot.cached() ?: hotSections))
                if (near != null && retry(near, "没有找到「$keyword」，试试「$near」 · ")) how = "你是不是想找「$near」"
            }

            // 3. Experimental, only when switched on in the settings: ask the AI for titles to search. What looks like a
            //    pasted link or code is never sent.
            var aiNote = ""
            if (grouper.size == 0 && okEver > 0 && Store.aiSearch() && Ai.allowed(keyword)) {
                status.text = "本地没搜到，正在请 AI 推荐片名（实验功能，通常要 10 秒左右）…"
                val answer = Ai.suggest(keyword)
                if (answer.titles.isNotEmpty()) {
                    val top = answer.titles.take(3)
                    also += answer.titles
                    round(top, "AI 推荐：${top.joinToString("、")} · ", 6_000)
                    if (grouper.size > 0) {
                        // Only the guesses that the sources really have are worth naming.
                        val have = top.filter { t -> raw.any { Search.matches(t, it.title) } }
                        how = "AI 推荐：${have.ifEmpty { top }.joinToString("、")}，仅供参考"
                    } else {
                        also.removeAll(answer.titles.toSet())
                        aiNote = "AI 推荐了${top.joinToString("、")}，但源里都没有"
                    }
                } else {
                    aiNote = answer.error ?: "AI 没有想到合适的片名"
                }
            }

            // 4. Last, the start of a long keyword: it nearly always finds something, so it must not come before the above.
            if (grouper.size == 0 && okEver > 0) {
                val head = Search.relaxed(plan.query).firstOrNull { it !in tried && it != keyword }
                if (head != null && retry(head, "没有找到「$keyword」，改搜「$head」 · ")) how = "按「$head」搜到"
            }

            progress.visibility = View.GONE
            val via = if (how.isEmpty() || grouper.size == 0) "" else "（$how）"
            status.text = "完成：$okEver/${sources.size} 个源可用，共 ${grouper.size} 个结果$via"
            if (grouper.size == 0) {
                empty.findViewById<ImageView>(R.id.emptyIcon).setImageResource(R.drawable.ic_search)
                empty.findViewById<TextView>(R.id.emptyTitle).text = "没有找到「$keyword」"
                empty.findViewById<TextView>(R.id.emptySub).text = when {
                    okEver == 0 -> "所有源都没有响应，检查一下网络，或到“设置”里更新订阅"
                    aiNote.isNotEmpty() -> aiNote
                    Store.aiSearch() -> "换个关键词，或者检查有没有错别字"
                    else -> "换个关键词，或者检查有没有错别字。\n也可以在“设置”里开启实验功能“AI 辅助搜索”"
                }
                empty.visibility = View.VISIBLE
            }
        }
    }

    /** Titles worth comparing a failed keyword with: what was watched and favorited, and what the charts show. */
    private suspend fun knownTitles(charts: List<Hot.Section>): Set<String> = withContext(Dispatchers.IO) {
        val out = LinkedHashSet<String>()
        // Not the search history: it holds the typos and dead ends this is meant to correct.
        Library.history(100).forEach { out += Cms.cleanTitle(it.title) }
        Library.favorites().forEach { out += Cms.cleanTitle(it.title) }
        charts.forEach { s -> s.items.forEach { out += Cms.cleanTitle(it.title) } }
        out
    }
}
