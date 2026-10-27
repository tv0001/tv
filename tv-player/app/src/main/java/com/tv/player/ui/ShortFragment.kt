package com.tv.player.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.tv.player.R
import com.tv.player.core.Grouper
import com.tv.player.core.Hot
import com.tv.player.core.ResultFilter
import com.tv.player.core.Search
import com.tv.player.core.ShortTopics
import com.tv.player.core.Store
import com.tv.player.data.VodGroup
import com.tv.player.data.VodItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The 短剧 tab: the sources' short-drama categories as one grid, newest first, page after page. The same title from
 * several sources becomes one card, so the player can switch between them like it does for search results.
 *
 * On top of that grid: a theme row and a search box, which both look for short dramas across every source (see
 * [ShortTopics]) and replace the grid with what they found, and filters (status, year, source, order) that work on
 * whichever list is showing.
 */
class ShortFragment : Fragment(R.layout.fragment_short) {
    private lateinit var adapter: VodGroupAdapter
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var empty: View
    private lateinit var queryBox: EditText
    private lateinit var topicChips: ChipGroup
    private lateinit var filterBox: View
    private lateinit var filterChips: ChipGroup

    /** Normalized title -> the items that share it, in the order first seen. Survives a recreated view. */
    private val groups = LinkedHashMap<String, ArrayList<VodItem>>()
    private var page = 0
    private var exhausted = false
    private var staleRounds = 0 // pages in a row that brought no new title
    private var job: Job? = null
    private var opening: Job? = null

    /** What a theme or the search box looked for; null while the plain grid is showing. */
    private class Query(val label: String, val keywords: List<String>)

    private var query: Query? = null
    private var found = Grouper(dedupe = true)
    private var searching = false
    private var queryJob: Job? = null
    private var topic = "" // the theme that is picked, "" for none
    private var autoPages = 0 // pages still to be fetched by themselves for a filter that leaves little on screen
    private val filter = ResultFilter()

    /** Thrown by the lookup in [open] once it has enough sources: stops the search early without being a cancellation. */
    private class Enough : Exception()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        list = view.findViewById(R.id.list)
        status = view.findViewById(R.id.status)
        progress = view.findViewById(R.id.progress)
        empty = view.findViewById(R.id.empty)
        queryBox = view.findViewById(R.id.shortQuery)
        topicChips = view.findViewById(R.id.topics)
        filterBox = view.findViewById(R.id.shortFilterBox)
        filterChips = view.findViewById(R.id.shortFilters)
        empty.findViewById<ImageView>(R.id.emptyIcon).setImageResource(R.drawable.ic_short)
        empty.setOnClickListener { if (query == null && groups.isEmpty()) reload() else if (filter.active) { filter.reset(); render() } }

        adapter = VodGroupAdapter(onClick = ::open)
        val ctx = requireContext()
        val span = ctx.gridSpan()
        list.layoutManager = GridLayoutManager(ctx, span)
        list.adapter = adapter
        list.isFocusable = false // RecyclerView overrides the layout's focusable="false"; only the cards take the focus
        if (ctx.isTv()) list.setPadding(ctx.dp(12), ctx.dp(16), ctx.dp(12), ctx.dp(16)) // room for the 1.1x focused card
        // The next page is asked for when the last two rows come into view, by touch or by the remote moving the focus.
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as GridLayoutManager
                if (query == null && adapter.itemCount > 0 && lm.findLastVisibleItemPosition() >= adapter.itemCount - span * 2) loadMore()
            }
        })
        status.doAfterTextChanged { status.visibility = if (it.isNullOrEmpty()) View.GONE else View.VISIBLE }
        // "没有更多了" may just mean every source was slow at that moment: a tap (or OK on the remote) asks again.
        status.setOnClickListener { if (query == null) { exhausted = false; loadMore() } }
        for (v in listOf(status, empty)) {
            v.isFocusable = true
            v.foreground = ContextCompat.getDrawable(ctx, R.drawable.bg_focus_row)
        }

        val clear = view.findViewById<View>(R.id.shortClear)
        queryBox.doAfterTextChanged { clear.visibility = if (it.isNullOrEmpty()) View.GONE else View.VISIBLE }
        clear.setOnClickListener {
            queryBox.text.clear()
            if (query != null && topic.isEmpty()) showGrid()
        }
        queryBox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { searchBox(); true } else false
        }
        view.findViewById<View>(R.id.shortSearchIcon).setOnClickListener { searchBox() }
        view.findViewById<View>(R.id.shortPhoneInput).apply {
            visibility = if (ctx.isTv()) View.VISIBLE else View.GONE
            setOnClickListener {
                PhonePush.show(requireActivity() as androidx.appcompat.app.AppCompatActivity) { if (view != null) { queryBox.setText(it); searchBox() } }
            }
        }
        buildTopics()

        // A recreated view (theme or language change, process restored): show what was loaded; the jobs of the old
        // view's scope are gone, so they must not block new ones.
        job = null
        opening = null
        queryJob = null
        if (searching) { searching = false; query?.let { runQuery(it.label, it.keywords) } }
        if (groups.isNotEmpty() || query != null) render() else if (!isHidden) loadMore()
    }

    override fun onDestroyView() {
        job = null
        opening = null
        queryJob = null
        super.onDestroyView()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // Created at start-up, loaded when first opened; an empty tab (nothing came back last time) tries again.
        if (!hidden && groups.isEmpty() && query == null) {
            exhausted = false
            loadMore()
        }
    }

    // ---- themes and search ---------------------------------------------------------------------------------------

    /** The chips are made once and only their checked state is updated, so the remote's focus stays where it is. */
    private fun buildTopics() {
        val ctx = requireContext()
        val names = listOf("全部") + ShortTopics.ALL.map { it.name }
        if (topicChips.childCount != names.size) {
            topicChips.removeAllViews()
            for (n in names) topicChips.addView(
                Chip(ctx).apply {
                    text = n
                    textSize = 13f
                    chipMinHeight = ctx.dp(34).toFloat()
                    isCheckable = true
                    setOnClickListener { pickTopic(n) }
                },
            )
        }
        names.forEachIndexed { i, n ->
            (topicChips.getChildAt(i) as Chip).isChecked = if (i == 0) topic.isEmpty() && query == null else topic == n
        }
    }

    private fun pickTopic(name: String) {
        hideKeyboard()
        queryBox.text.clear()
        val t = ShortTopics.ALL.firstOrNull { it.name == name }
        if (t == null) {
            showGrid()
            return
        }
        topic = t.name
        runQuery(t.name, t.keywords)
        buildTopics()
    }

    /** Back to the plain grid of what the sources updated last. */
    private fun showGrid() {
        queryJob?.cancel()
        queryJob = null
        query = null
        topic = ""
        searching = false
        filter.reset()
        autoPages = 0
        progress.visibility = if (job != null) View.VISIBLE else View.GONE
        status.text = if (exhausted && groups.isNotEmpty()) "共 ${groups.size} 部，没有更多了。点这里再查一次" else ""
        buildTopics()
        render() // first: whatever the last theme left on screen must go, even if there is nothing to load
        if (groups.isEmpty() && job == null) loadMore()
    }

    private fun searchBox() {
        val text = Search.clean(queryBox.text.toString())
        if (text.isEmpty()) {
            if (query != null && topic.isEmpty()) showGrid() // a theme that is picked stays: the box was empty all along
            return
        }
        hideKeyboard()
        topic = ""
        runQuery(text, listOf(text))
        buildTopics()
    }

    private fun hideKeyboard() {
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(queryBox.windowToken, 0)
    }

    /**
     * Looks for short dramas whose titles have any of [keywords], in every source at once. What comes back is kept to the
     * short-drama category, merged by title like everywhere, and shown while it arrives.
     */
    private fun runQuery(label: String, keywords: List<String>) {
        queryJob?.cancel()
        val sources = Store.sources()
        query = Query(label, keywords)
        found = Grouper(dedupe = true)
        filter.reset()
        searching = true
        progress.visibility = View.VISIBLE
        status.text = "正在找「$label」的短剧…"
        render()
        if (sources.isEmpty()) {
            searching = false
            progress.visibility = View.GONE
            status.text = "没有可用的点播源，去“设置”里更新订阅"
            return
        }
        queryJob = viewLifecycleOwner.lifecycleScope.launch {
            var done = 0
            val total = sources.size * keywords.size
            // Only what the user typed counts towards a source's cool-down; a theme is many searches from one tap, and
            // the chips are easy to tap one after another.
            keywords.map { k -> Search.all(sources, k, trackHealth = keywords.size == 1) }.merge().collect { hit ->
                done++
                found.add(hit.items.filter { ShortTopics.isShort(it.kind) })
                status.text = "「$label」已返回 $done/$total 个源 · ${found.size} 部短剧"
                render()
            }
            searching = false
            progress.visibility = View.GONE
            status.text = if (found.size == 0) "" else "「$label」共 ${found.size} 部短剧（各源的搜索结果）"
            render()
        }
    }

    // ---- list and filters ----------------------------------------------------------------------------------------

    private fun feed(): List<VodGroup> = groups.map { (key, g) ->
        VodGroup(key, g.first().title, g.firstOrNull { it.pic.isNotEmpty() }?.pic.orEmpty(), g.firstOrNull { it.remarks.isNotEmpty() }?.remarks.orEmpty(), g.toList())
    }

    private fun current(): List<VodGroup> {
        val q = query ?: return feed()
        return found.snapshot(q.keywords.first(), q.keywords.drop(1), kind = "短剧")
    }

    /** The list on show, through the filters, with the filter row and the empty state that go with it. */
    private fun render() {
        if (view == null) return
        val all = current()
        val shown = filter.apply(all)
        adapter.submitList(shown)
        buildFilters(all, shown.size)
        val loading = searching || (query == null && job != null)
        val q = query
        if (shown.isEmpty() && !loading) {
            val title: String
            val sub: String
            when {
                all.isNotEmpty() -> { title = "没有符合筛选的短剧"; sub = "点这里清除筛选" }
                q != null -> { title = "没有找到「${q.label}」的短剧"; sub = "换个题材或关键词试试" }
                else -> { title = "没有拿到短剧"; sub = "点播源里没有短剧分类，或者现在连不上。\n点这里再试一次" }
            }
            empty.findViewById<TextView>(R.id.emptyTitle).text = title
            empty.findViewById<TextView>(R.id.emptySub).text = sub
            empty.visibility = View.VISIBLE
        } else {
            empty.visibility = View.GONE
        }
        // A filter that leaves little on screen while there are more pages: fetch on, instead of waiting for a scroll that
        // a short list never gets.
        if (q == null && filter.active && shown.size < 9 && !exhausted && job == null && groups.isNotEmpty() && !isHidden && autoPages > 0) {
            autoPages--
            loadMore()
        }
    }

    /** A filter was picked or reset: show it, and allow a few pages to be fetched on its behalf (not an endless crawl). */
    private fun filterChanged() {
        autoPages = 5
        render()
    }

    private class Spec(val key: String, val text: String, val active: Boolean, val onClick: () -> Unit)

    /** The filter row. Its chips are kept between calls (the list renders on every answer) so the focus does not jump. */
    private fun buildFilters(all: List<VodGroup>, shown: Int) {
        if (all.size < 2 && !filter.active) {
            filterChips.removeAllViews()
            filterBox.visibility = View.GONE
            return
        }
        val ctx = requireContext()
        fun pick(title: String, options: List<String>, checked: Int, onPick: (Int) -> Unit) {
            MaterialAlertDialogBuilder(ctx)
                .setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), checked) { d, i ->
                    onPick(i)
                    d.dismiss()
                    filterChanged()
                }
                .show()
        }
        val specs = ArrayList<Spec>()
        specs += Spec("sort", "排序：${ResultFilter.SORTS[filter.sort]}", filter.sort != 0) {
            pick("排序", ResultFilter.SORTS, filter.sort) { filter.sort = it }
        }
        specs += Spec("status", if (filter.status == 0) "状态" else "状态：${ResultFilter.STATUSES[filter.status]}", filter.status != 0) {
            pick("状态（按片源备注判断）", ResultFilter.STATUSES, filter.status) { filter.status = it }
        }
        val years = filter.years(all)
        if (years.isNotEmpty()) specs += Spec("year", if (filter.year.isEmpty()) "年份" else "年份：${filter.year}", filter.year.isNotEmpty()) {
            val options = listOf("全部") + years
            pick("年份", options, options.indexOf(filter.year).coerceAtLeast(0)) { filter.year = if (it == 0) "" else options[it] }
        }
        val sources = filter.sources(all)
        if (sources.size >= 2 || filter.source.isNotEmpty()) specs += Spec("source", if (filter.source.isEmpty()) "来源" else "来源：${filter.source}", filter.source.isNotEmpty()) {
            val options = listOf("全部") + sources
            pick("来源", options, options.indexOf(filter.source).coerceAtLeast(0)) { filter.source = if (it == 0) "" else options[it] }
        }
        if (filter.active) specs += Spec("reset", "重置（$shown/${all.size}）", false) {
            filter.reset()
            filterChanged()
        }
        val same = filterChips.childCount == specs.size && specs.indices.all { filterChips.getChildAt(it).tag == specs[it].key }
        if (!same) {
            filterChips.removeAllViews()
            for (sp in specs) filterChips.addView(
                Chip(ctx).apply {
                    tag = sp.key
                    textSize = 13f
                    chipMinHeight = ctx.dp(34).toFloat()
                    isCheckable = true
                },
            )
        }
        specs.forEachIndexed { i, sp ->
            val chip = filterChips.getChildAt(i) as Chip
            chip.text = sp.text
            chip.isChecked = sp.active
            chip.setOnClickListener {
                chip.isChecked = sp.active // the dialog decides; do not flip by itself
                sp.onClick()
            }
        }
        filterBox.visibility = View.VISIBLE
    }

    // ---- opening a card, the grid's paging -----------------------------------------------------------------------

    /**
     * A card from one source has no line to fall back to, so before playing, the same title is looked up on every
     * source for a moment: at most 3 s, or until four sources have it. Whatever answered by then joins the card; the
     * lookup itself is dropped (its HTTP calls cannot be interrupted, so it is not waited for).
     */
    private fun open(group: VodGroup) {
        opening?.cancel()
        val sources = Store.sources()
        val before = status.text.toString()
        status.text = "正在找「${group.title}」的线路…"
        progress.visibility = View.VISIBLE
        opening = viewLifecycleOwner.lifecycleScope.launch {
            val key = Search.normalize(group.title)
            val found = ArrayList(group.items) // only touched on the main thread: the collector runs here too
            if (sources.isNotEmpty()) {
                val lookup = launch {
                    try {
                        Search.all(sources, group.title, perSourceMs = 3_000).collect { hit ->
                            for (i in hit.items) {
                                if (Search.normalize(i.title) == key && found.none { it.sourceKey == i.sourceKey && it.id == i.id }) found += i
                            }
                            if (found.size >= 4) throw Enough()
                        }
                    } catch (e: Enough) {
                        // enough sources, stop looking
                    }
                }
                withTimeoutOrNull(3_000) { lookup.join() }
                lookup.cancel()
            }
            progress.visibility = if (searching || job != null) View.VISIBLE else View.GONE
            status.text = before
            PlayActivity.startVod(requireContext(), group.copy(items = found.toList()))
        }
    }

    private fun reload() {
        job?.cancel()
        job = null
        groups.clear()
        page = 0
        exhausted = false
        staleRounds = 0
        adapter.submitList(emptyList())
        loadMore()
    }

    private fun loadMore() {
        // While a theme or a search is on screen the grid is left alone (it is picked up again by 全部): this must not
        // touch the progress bar or the empty state that belong to the query.
        if (job != null || exhausted || query != null) return
        empty.visibility = View.GONE
        progress.visibility = View.VISIBLE
        status.text = if (page == 0) "正在加载短剧…" else "正在加载更多…"
        job = viewLifecycleOwner.lifecycleScope.launch {
            val items = Hot.shortPage(page + 1)
            page++
            var added = 0
            for (i in items) {
                val g = groups.getOrPut(Search.normalize(i.title).ifEmpty { i.title }) { added++; ArrayList() }
                if (g.none { it.sourceKey == i.sourceKey && it.id == i.id }) g += i
            }
            // A page of titles already seen (sources that list the same thing on every page) would leave the grid
            // as it is and nothing would ask for the next page; three such pages in a row mean there is no more.
            staleRounds = if (added == 0) staleRounds + 1 else 0
            if (items.isEmpty() || staleRounds >= 3) exhausted = true
            job = null
            if (query == null) {
                progress.visibility = View.GONE
                status.text = if (exhausted && groups.isNotEmpty()) "共 ${groups.size} 部，没有更多了。点这里再查一次" else ""
            }
            render()
            if (added == 0 && !exhausted) loadMore()
        }
    }
}
