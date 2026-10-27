package com.tv.player.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import com.tv.player.core.Epg
import coil.transform.RoundedCornersTransformation
import com.tv.player.R
import com.tv.player.core.Hot
import com.tv.player.core.Http
import com.tv.player.core.Store
import com.tv.player.data.LiveChannel
import com.tv.player.data.VodGroup

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

fun Context.isTv(): Boolean = when (Store.uiMode()) {
    "tv" -> true
    "phone" -> false
    else -> (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

/** Tablet: "auto" means a phone-sized-or-bigger screen with a smallest width of 600dp or more that is not a TV. */
fun Context.isTablet(): Boolean = when (Store.uiMode()) {
    "tablet" -> true
    "auto" -> resources.configuration.smallestScreenWidthDp >= 600 && !isTv()
    else -> false
}

/** TV and tablet get the side navigation rail instead of the bottom bar. */
fun Context.useSideNav(): Boolean = isTv() || isTablet()

/** Poster width in dp: bigger on TV (seen from the sofa) and tablets. */
fun Context.posterDp(): Int = if (isTv()) 170 else if (isTablet()) 140 else 108

/** Number of grid columns that keep posters around [posterDp] wide. */
fun Context.gridSpan(minSpan: Int = 3): Int =
    maxOf(minSpan, (resources.displayMetrics.widthPixels / resources.displayMetrics.density / posterDp()).toInt())

/** Remote-control focus feedback: the focused card grows a little. */
fun View.zoomOnFocus(scale: Float = 1.06f) {
    setOnFocusChangeListener { v, focused ->
        v.animate().scaleX(if (focused) scale else 1f).scaleY(if (focused) scale else 1f).setDuration(120).start()
    }
}

class VodGroupAdapter(
    private val onClick: (VodGroup) -> Unit,
    private val onLongClick: ((VodGroup) -> Unit)? = null,
) : ListAdapter<VodGroup, VodGroupAdapter.Holder>(Diff) {

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val poster: ImageView = v.findViewById(R.id.poster)
        val badge: TextView = v.findViewById(R.id.badge)
        val remarks: TextView = v.findViewById(R.id.remarks)
        val title: TextView = v.findViewById(R.id.title)
        val subtitle: TextView = v.findViewById(R.id.subtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_vod, parent, false))

    override fun onBindViewHolder(h: Holder, position: Int) {
        val g = getItem(position)
        h.poster.load(g.pic.takeIf { it.isNotEmpty() }) {
            crossfade(true)
            transformations(RoundedCornersTransformation(h.itemView.context.dp(8).toFloat()))
        }
        val many = g.items.size > 1
        h.badge.visibility = if (g.badge.isNotEmpty() || many) View.VISIBLE else View.GONE
        h.badge.text = g.badge.ifEmpty { "${g.items.size} 个源" }
        // "新" (new episodes) is news and gets the accent; the source count is just information.
        h.badge.setBackgroundResource(if (g.badge.isNotEmpty()) R.drawable.bg_badge else R.drawable.bg_badge_muted)
        h.badge.setTextColor(if (g.badge.isNotEmpty()) 0xFF00224D.toInt() else android.graphics.Color.WHITE)
        h.remarks.text = g.remarks
        h.title.text = g.title
        h.subtitle.text = g.items.map { it.sourceName }.distinct().joinToString(" · ")
        h.itemView.zoomOnFocus()
        h.itemView.setOnClickListener { onClick(g) }
        h.itemView.setOnLongClickListener {
            onLongClick?.invoke(g)
            onLongClick != null
        }
    }

    private object Diff : DiffUtil.ItemCallback<VodGroup>() {
        override fun areItemsTheSame(a: VodGroup, b: VodGroup) = a.key == b.key
        override fun areContentsTheSame(a: VodGroup, b: VodGroup) = a == b
    }
}

/** Poster cards for the hot charts; reuses the search card layout at a fixed width. */
class HotAdapter(
    private val onClick: (Hot.Item) -> Unit,
    private val grid: Boolean = false,
    /** A narrower poster than the usual one (dp), for a compact row. */
    private val widthDp: Int? = null,
) : ListAdapter<Hot.Item, VodGroupAdapter.Holder>(Diff) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VodGroupAdapter.Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_vod, parent, false)
        view.layoutParams = RecyclerView.LayoutParams(
            if (grid) ViewGroup.LayoutParams.MATCH_PARENT else parent.context.dp(widthDp ?: if (parent.context.isTv()) 128 else if (parent.context.isTablet()) 124 else 104),
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        return VodGroupAdapter.Holder(view)
    }

    override fun onBindViewHolder(h: VodGroupAdapter.Holder, position: Int) {
        val item = getItem(position)
        h.poster.load(item.pic.takeIf { it.isNotEmpty() }) {
            addHeader("User-Agent", Http.UA)
            if ("doubanio.com" in item.pic) addHeader("Referer", Hot.IMAGE_REFERER)
            crossfade(true)
            transformations(RoundedCornersTransformation(h.itemView.context.dp(8).toFloat()))
        }
        h.badge.visibility = View.GONE
        h.remarks.text = if (item.rating > 0) "★ %.1f".format(java.util.Locale.ROOT, item.rating) else item.remarks
        h.title.text = item.title
        val year = item.sub.substringBefore(" / ") // older caches kept the whole "2026 / 中国大陆 / …" line
        h.subtitle.text = year
        h.subtitle.visibility = if (year.isEmpty()) View.GONE else View.VISIBLE
        // On TV the focused card grows more, so it stands out from the row.
        h.itemView.zoomOnFocus(if (h.itemView.context.isTv()) 1.1f else 1.06f)
        h.itemView.setOnClickListener { onClick(item) }
    }

    private object Diff : DiffUtil.ItemCallback<Hot.Item>() {
        override fun areItemsTheSame(a: Hot.Item, b: Hot.Item) = a.title == b.title
        override fun areContentsTheSame(a: Hot.Item, b: Hot.Item) = a.title == b.title && a.pic == b.pic && a.rating == b.rating
    }
}

class ChannelAdapter(
    private val onClick: (LiveChannel) -> Unit,
    /** TV: rows that are shown also say what is on now (from the programme guide); null leaves that out. */
    private val scope: CoroutineScope? = null,
) : ListAdapter<LiveChannel, ChannelAdapter.Holder>(Diff) {

    private val gate = Semaphore(3) // a few guide lookups at a time, not one per row at once
    private val asked = HashSet<String>() // channels already asked, so a guide that has none is not asked again at every scroll

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: TextView = v.findViewById(R.id.avatar)
        val name: TextView = v.findViewById(R.id.name)
        val lines: TextView = v.findViewById(R.id.lines)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))

    override fun onBindViewHolder(h: Holder, position: Int) {
        val c = getItem(position)
        h.avatar.text = abbreviation(c.name)
        h.name.text = c.name
        val now = Epg.cached(c.name)?.current?.title
        h.lines.text = describe(c, now)
        if (now == null && scope != null && asked.add(c.name)) {
            scope.launch {
                val title = gate.withPermit { Epg.now(c.name) }?.current?.title ?: return@launch
                val at = h.bindingAdapterPosition
                // The row may have been reused for another channel by the time the answer comes.
                if (at >= 0 && getItem(at).name == c.name) h.lines.text = describe(c, title)
            }
        }
        h.itemView.setOnClickListener { onClick(c) }
    }

    private fun describe(c: LiveChannel, now: String?) =
        if (now == null) "${c.urls.size} 条线路" else "现在：$now  ·  ${c.urls.size} 条线路"

    private fun abbreviation(name: String): String {
        if (name.startsWith("CCTV", ignoreCase = true)) return name.substring(4).trim().take(3).ifEmpty { "TV" }
        return name.take(2)
    }

    private object Diff : DiffUtil.ItemCallback<LiveChannel>() {
        override fun areItemsTheSame(a: LiveChannel, b: LiveChannel) = a.group == b.group && a.name == b.name
        override fun areContentsTheSame(a: LiveChannel, b: LiveChannel) = a == b
    }
}

/** A row of selectable text chips, used for play lines and episodes. */
/** [fill]: cells take the full grid column (episodes), otherwise they size to their text (lines). */
/**
 * A list for inside an AlertDialog. The dialog gives a custom view all the room that is left and ignores the height it asks
 * for, so a long list pushes the buttons under it off the bottom on some screens. Measured with a ceiling instead, the
 * dialog is as tall as title + list + buttons need and the buttons always have their place.
 */
class BoundedRecyclerView(context: Context, private val maxHeight: Int) : RecyclerView(context) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        // Never taller than the ceiling, nor than the room the parent offers (a split screen or a small window).
        val room = if (MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED) maxHeight else minOf(maxHeight, MeasureSpec.getSize(heightSpec))
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(room, MeasureSpec.AT_MOST))
    }
}

class ChipAdapter(var fill: Boolean = false, private val onClick: (Int) -> Unit) : RecyclerView.Adapter<ChipAdapter.Holder>() {
    class Holder(val text: TextView) : RecyclerView.ViewHolder(text)

    private var labels: List<String> = emptyList()
    private var selected = -1

    // Stable ids keep the focused chip in place across submit(); otherwise a remote's focus jumps off the row.
    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int) = position.toLong()

    fun submit(list: List<String>, selectedIndex: Int) {
        labels = list
        selected = selectedIndex
        notifyDataSetChanged()
    }

    override fun getItemCount() = labels.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val text = LayoutInflater.from(parent.context).inflate(R.layout.item_chip, parent, false) as TextView
        if (fill) text.layoutParams = text.layoutParams.apply { width = ViewGroup.LayoutParams.MATCH_PARENT }
        return Holder(text)
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        h.text.text = labels[position]
        h.text.isSelected = position == selected
        h.text.setOnClickListener { onClick(h.bindingAdapterPosition) }
    }
}
