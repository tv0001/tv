package com.tv.player.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.tv.player.R
import com.tv.player.core.Store
import com.tv.player.data.LiveChannel

class LiveFragment : Fragment(R.layout.fragment_live) {
    private var channels: List<LiveChannel> = emptyList()
    private var selectedGroup: String? = null
    private lateinit var adapter: ChannelAdapter
    private lateinit var groups: ChipGroup
    private lateinit var subtitle: TextView
    private lateinit var empty: View
    private var loadedRevision = -1

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        groups = view.findViewById(R.id.groups)
        subtitle = view.findViewById(R.id.subtitle)
        empty = view.findViewById(R.id.empty)
        empty.findViewById<ImageView>(R.id.emptyIcon).setImageResource(R.drawable.ic_live)
        empty.findViewById<TextView>(R.id.emptyTitle).text = "没有直播频道"
        empty.findViewById<TextView>(R.id.emptySub).text = "去“设置”里更新订阅，或在“源管理”里添加直播源"

        adapter = ChannelAdapter { PlayActivity.startLive(requireContext(), it) }
        view.findViewById<RecyclerView>(R.id.channels).apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = this@LiveFragment.adapter
        }
        groups.setOnCheckedStateChangeListener { _, ids ->
            val chip = ids.firstOrNull()?.let { groups.findViewById<Chip>(it) } ?: return@setOnCheckedStateChangeListener
            selectedGroup = chip.text.toString()
            showGroup()
        }
        load()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) load()
    }

    private fun load() {
        val revision = Store.liveRevision()
        if (revision == loadedRevision) return
        loadedRevision = revision
        channels = Store.channels()
        subtitle.text = "${channels.size} 个频道 · 每个频道多条线路，卡住会自动切换"
        val names = channels.map { it.group }.distinct()
        if (selectedGroup !in names) selectedGroup = names.firstOrNull()

        groups.removeAllViews()
        for (name in names) {
            val chip = LayoutInflater.from(requireContext()).inflate(R.layout.chip_filter, groups, false) as Chip
            chip.id = View.generateViewId()
            chip.text = name
            groups.addView(chip)
            if (name == selectedGroup) groups.check(chip.id)
        }
        showGroup()
    }

    private fun showGroup() {
        val list = channels.filter { it.group == selectedGroup }
        adapter.submitList(list)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }
}
