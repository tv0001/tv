package com.tv.player.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.tv.player.R
import com.tv.player.core.Hot
import com.tv.player.core.Search
import com.tv.player.core.Store
import com.tv.player.data.VodGroup
import com.tv.player.data.VodItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The 短剧 tab: the sources' short-drama categories as one grid, newest first, page after page. The same title from
 * several sources becomes one card, so the player can switch between them like it does for search results.
 */
class ShortFragment : Fragment(R.layout.fragment_short) {
    private lateinit var adapter: VodGroupAdapter
    private lateinit var list: RecyclerView
    private lateinit var status: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var empty: View

    /** Normalized title -> the items that share it, in the order first seen. Survives a recreated view. */
    private val groups = LinkedHashMap<String, ArrayList<VodItem>>()
    private var page = 0
    private var exhausted = false
    private var staleRounds = 0 // pages in a row that brought no new title
    private var job: Job? = null
    private var opening: Job? = null

    /** Thrown by the lookup in [open] once it has enough sources: stops the search early without being a cancellation. */
    private class Enough : Exception()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        list = view.findViewById(R.id.list)
        status = view.findViewById(R.id.status)
        progress = view.findViewById(R.id.progress)
        empty = view.findViewById(R.id.empty)
        empty.findViewById<ImageView>(R.id.emptyIcon).setImageResource(R.drawable.ic_short)
        empty.findViewById<TextView>(R.id.emptyTitle).text = "没有拿到短剧"
        empty.findViewById<TextView>(R.id.emptySub).text = "点播源里没有短剧分类，或者现在连不上。\n点这里再试一次"
        empty.setOnClickListener { reload() }

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
                if (adapter.itemCount > 0 && lm.findLastVisibleItemPosition() >= adapter.itemCount - span * 2) loadMore()
            }
        })
        status.doAfterTextChanged { status.visibility = if (it.isNullOrEmpty()) View.GONE else View.VISIBLE }
        // "没有更多了" may just mean every source was slow at that moment: a tap (or OK on the remote) asks again.
        status.setOnClickListener { exhausted = false; loadMore() }
        for (v in listOf(status, empty)) {
            v.isFocusable = true
            v.foreground = ContextCompat.getDrawable(ctx, R.drawable.bg_focus_row)
        }

        // A recreated view (theme or language change, process restored): show what was loaded; the jobs of the old
        // view's scope are gone, so they must not block new ones.
        job = null
        opening = null
        if (groups.isNotEmpty()) submit() else if (!isHidden) loadMore()
    }

    override fun onDestroyView() {
        job = null
        opening = null
        super.onDestroyView()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // Created at start-up, loaded when first opened; an empty tab (nothing came back last time) tries again.
        if (!hidden && groups.isEmpty()) {
            exhausted = false
            loadMore()
        }
    }

    /**
     * A card from one source has no line to fall back to, so before playing, the same title is looked up on every
     * source for a moment: at most 3 s, or until four sources have it. Whatever answered by then joins the card; the
     * lookup itself is dropped (its HTTP calls cannot be interrupted, so it is not waited for).
     */
    private fun open(group: VodGroup) {
        opening?.cancel()
        val sources = Store.sources()
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
            progress.visibility = View.GONE
            status.text = ""
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

    private fun submit() {
        adapter.submitList(groups.map { (key, g) ->
            VodGroup(key, g.first().title, g.firstOrNull { it.pic.isNotEmpty() }?.pic.orEmpty(), g.firstOrNull { it.remarks.isNotEmpty() }?.remarks.orEmpty(), g.toList())
        })
    }

    private fun loadMore() {
        if (job != null || exhausted) return
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
            submit()
            progress.visibility = View.GONE
            status.text = if (exhausted && groups.isNotEmpty()) "共 ${groups.size} 部，没有更多了。点这里再查一次" else ""
            empty.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
            job = null
            if (added == 0 && !exhausted) loadMore()
        }
    }
}
