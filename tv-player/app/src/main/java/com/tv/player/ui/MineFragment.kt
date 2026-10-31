package com.tv.player.ui

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tv.player.R
import com.tv.player.core.Library
import com.tv.player.core.Tracker
import com.tv.player.data.VodGroup
import kotlinx.coroutines.launch

class MineFragment : Fragment(R.layout.fragment_mine) {
    private var showFavorites = false
    private lateinit var adapter: VodGroupAdapter
    private lateinit var empty: View

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        empty = view.findViewById(R.id.empty)
        empty.findViewById<ImageView>(R.id.emptyIcon).setImageResource(R.drawable.ic_heart_outline)

        adapter = VodGroupAdapter(
            onClick = { PlayActivity.startVod(requireContext(), it) },
            onLongClick = ::confirmRemove,
        )
        view.findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = GridLayoutManager(requireContext(), requireContext().gridSpan())
            adapter = this@MineFragment.adapter
        }
        view.findViewById<View>(R.id.panButton).setOnClickListener { PanActivity.start(requireContext()) }
        view.findViewById<View>(R.id.netButton).setOnClickListener { NetStoreActivity.start(requireContext()) }
        view.findViewById<View>(R.id.downloadButton).apply {
            // 离线下载是给手机、平板出门看的，电视上不放
            visibility = if (requireContext().isTv()) View.GONE else View.VISIBLE
            setOnClickListener { DownloadsActivity.start(requireContext()) }
        }
        view.findViewById<ChipGroup>(R.id.mode).setOnCheckedStateChangeListener { _, ids ->
            showFavorites = ids.firstOrNull() == R.id.chipFav
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            refresh()
            checkNew()
        }
    }

    /** Looks for new episodes of the favorites (at most hourly) and refreshes the cards and the tab badge. */
    private fun checkNew() {
        if (!Tracker.due()) return
        viewLifecycleOwner.lifecycleScope.launch {
            Tracker.check()
            refresh()
            (activity as? MainActivity)?.updateMineBadge()
        }
    }

    private fun refresh() {
        if (view == null) return
        val entries = if (showFavorites) Library.favorites().sortedByDescending { it.fresh > 0 } else Library.history()
        adapter.submitList(entries.map { if (showFavorites) it.toFavoriteGroup() else it.toGroup(withProgress = true) })
        empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        empty.findViewById<TextView>(R.id.emptyTitle).text = if (showFavorites) "还没有收藏" else "还没有观看记录"
        empty.findViewById<TextView>(R.id.emptySub).text =
            if (showFavorites) "在播放页点“收藏”，会出现在这里" else "看过的影片会记住进度，下次接着看"
    }

    private fun confirmRemove(group: VodGroup) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("移除「${group.title}」？")
            .setMessage(if (showFavorites) "从收藏中移除" else "从观看记录中移除，进度也会清掉")
            .setNegativeButton("取消", null)
            .setPositiveButton("移除") { _, _ ->
                Library.remove(showFavorites, group.key)
                if (!showFavorites) WatchNext.remove(requireContext(), group.key)
                refresh()
            }
            .show()
    }
}
