package com.tv.player.ui

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
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

/**
 * 布局里 android:foreground 画的焦点框：普通布局（不是 FrameLayout）要 Android 6.0 才画前景，兼容包在更老的系统上
 * 把它画成背景（这些条目的根布局本来没有背景，内容四周留了边距，框仍然露得出来）。新系统上什么都不做。
 */
fun View.legacyFocusRing(): View = apply {
    if (Build.VERSION.SDK_INT < 23 && this !is android.widget.FrameLayout && background == null) {
        background = ContextCompat.getDrawable(context, R.drawable.bg_focus)
    }
}

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

/**
 * 横着滑的一行海报（电视上用遥控器左右移焦点）。焦点卡片走出屏幕时才滚，而且只滚到它刚好露出来（RecyclerView 默认的做法）。
 *
 * 这一行的高度是 wrap_content，外面几层 LinearLayout / NestedScrollView 量尺寸时会拿不是真实宽度的尺寸（实测有 174px、
 * 1032px 不限宽）让它先排一遍。在那么窄的范围里，焦点卡片为了露出来被挪到行首；真正排版时又沿用了这个位置，
 * 看起来就是每按一下右键，整行都把焦点卡片对齐到最左边（1.0.35 及以前都这样）。所以宽度不是真实宽度的那几遍，
 * 排完把卡片挪回原位。
 */
class FocusRowLayoutManager(context: Context) : androidx.recyclerview.widget.LinearLayoutManager(context, HORIZONTAL, false) {
    private var list: RecyclerView? = null

    override fun onAttachedToWindow(view: RecyclerView) {
        super.onAttachedToWindow(view)
        list = view
    }

    override fun onDetachedFromWindow(view: RecyclerView, recycler: RecyclerView.Recycler) {
        super.onDetachedFromWindow(view, recycler)
        list = null
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State) {
        val before = focusedChild?.let { getDecoratedLeft(it) }
        super.onLayoutChildren(recycler, state)
        // 从右往左的语言里这一行按右边缘排，按左边缘挪回去反而会把卡片推出屏幕：不管
        if (layoutDirection == View.LAYOUT_DIRECTION_RTL) return
        val real = list?.width ?: 0
        if (before == null || real <= 0 || width == real || state.didStructureChange()) return
        val after = focusedChild?.let { getDecoratedLeft(it) } ?: return
        if (after != before) offsetChildrenHorizontal(before - after)
    }
}

/** Remote-control focus feedback: the focused card grows a little. */
fun View.zoomOnFocus(scale: Float = 1.06f) {
    setOnFocusChangeListener { v, focused ->
        // 电视上片名放不下时，拿到焦点就滚动显示全名（跑马灯），离开恢复省略号
        v.findViewById<TextView?>(R.id.title)?.takeIf { v.context.isTv() }?.let { t ->
            t.isSingleLine = true
            t.marqueeRepeatLimit = -1
            t.ellipsize = if (focused) android.text.TextUtils.TruncateAt.MARQUEE else android.text.TextUtils.TruncateAt.END
            t.isSelected = focused
        }
        // 插值器要每次写明：同一个 View 的 animate() 会沿用上一次设的（按压回弹用的是 Overshoot）
        v.animate().scaleX(if (focused) scale else 1f).scaleY(if (focused) scale else 1f).setDuration(120)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator()).start()
    }
    // 手指按下时海报轻轻缩一下、松开弹回（腾讯视频、B 站都有），点没点上一眼就知道；滑动列表时 CANCEL 也会弹回
    if (getTag(R.id.pressScale) == null) {
        setTag(R.id.pressScale, true)
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
            }
            false
        }
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
        val watched: ProgressBar = v.findViewById(R.id.watched)

        /** 继续观看的进度条：[progress] 在 0..1 之间才显示（太靠前的不画，免得只有一个点） */
        fun showProgress(progress: Float) {
            watched.visibility = if (progress >= 0.01f) View.VISIBLE else View.GONE
            if (progress >= 0.01f) watched.progress = (progress * 1000).toInt()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_vod, parent, false).legacyFocusRing())

    override fun onBindViewHolder(h: Holder, position: Int) {
        val g = getItem(position)
        h.poster.load(g.pic.takeIf { it.isNotEmpty() }) {
            crossfade(true)
            fallback(R.drawable.bg_poster_placeholder)
            error(R.drawable.bg_poster_placeholder)
            transformations(RoundedCornersTransformation(h.itemView.context.dp(8).toFloat()))
        }
        val many = g.items.size > 1
        h.badge.visibility = if (g.badge.isNotEmpty() || many) View.VISIBLE else View.GONE
        h.badge.text = g.badge.ifEmpty { "${g.items.size} 个源" }
        // "新" (new episodes) is news and gets the accent; the source count is just information.
        h.badge.setBackgroundResource(if (g.badge.isNotEmpty()) R.drawable.bg_badge else R.drawable.bg_badge_muted)
        h.badge.setTextColor(if (g.badge.isNotEmpty()) 0xFF00224D.toInt() else android.graphics.Color.WHITE)
        h.remarks.text = g.remarks
        h.showProgress(g.progress)
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
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_vod, parent, false).legacyFocusRing()
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
            fallback(R.drawable.bg_poster_placeholder)
            error(R.drawable.bg_poster_placeholder)
            transformations(RoundedCornersTransformation(h.itemView.context.dp(8).toFloat()))
        }
        h.badge.visibility = View.GONE
        h.remarks.text = if (item.rating > 0) "★ %.1f".format(java.util.Locale.ROOT, item.rating) else item.remarks
        h.showProgress(item.progress)
        h.title.text = item.title
        val year = item.sub.substringBefore(" / ") // older caches kept the whole "2026 / 中国大陆 / …" line
        h.subtitle.text = year
        h.subtitle.visibility = if (year.isEmpty()) View.GONE else View.VISIBLE
        // On TV the focused card grows more, so it stands out from the row.
        h.itemView.zoomOnFocus(if (h.itemView.context.isTv()) 1.1f else 1.06f)
        // 点的时候再按位置取：列表换成内容相同的新对象时（从播放页回来刷新「继续观看」）卡片不会重新绑定，
        // 绑定时记下的旧对象和列表里的对不上，按对象找观看记录会落空、退成按片名搜索
        h.itemView.setOnClickListener {
            val pos = h.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(getItem(pos))
        }
    }

    private object Diff : DiffUtil.ItemCallback<Hot.Item>() {
        override fun areItemsTheSame(a: Hot.Item, b: Hot.Item) = a.title == b.title
        override fun areContentsTheSame(a: Hot.Item, b: Hot.Item) = a.title == b.title && a.pic == b.pic && a.rating == b.rating && a.progress == b.progress && a.sub == b.sub && a.remarks == b.remarks
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

    /** 正在播的那一格在名字前画跳动的音柱（腾讯视频、爱奇艺的选集），只用在手机、平板的选集上 */
    var playingMark = false

    override fun onBindViewHolder(h: Holder, position: Int) {
        val label = labels[position]
        h.text.text = if (playingMark && position == selected) {
            val bars = PlayingBars(h.text)
            android.text.SpannableString("  $label").apply {
                setSpan(android.text.style.ImageSpan(bars, android.text.style.ImageSpan.ALIGN_BASELINE), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else label
        h.text.isSelected = position == selected
        h.text.setOnClickListener { onClick(h.bindingAdapterPosition) }
    }
}

/**
 * 三根上下跳的竖条。画在文字里（ImageSpan），自己按 15 帧左右请求重画：格子滚出屏幕或换成别的字就不再画，循环也就停了。
 * 系统关掉动画时画成静止的三根。
 */
class PlayingBars(private val host: View) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = androidx.core.content.ContextCompat.getColor(host.context, R.color.accent)
    }
    // 同一时间只挂一次重画：列表滑动、水波纹这些额外的重画不会各自再起一条循环
    private val next = Runnable { host.invalidate() }
    private val still = android.provider.Settings.Global.getFloat(
        host.context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
    ) == 0f

    init {
        val size = host.context.dp(12)
        setBounds(0, 0, size, size)
    }

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        val gap = b.width() / 7f
        val w = (b.width() - gap * 2) / 3f
        val t = if (still) 0.0 else android.os.SystemClock.uptimeMillis() / 1000.0
        for (i in 0 until 3) {
            // 三根各自的节奏错开，看起来像在跳
            val k = if (still) listOf(0.6, 1.0, 0.4)[i] else 0.35 + 0.65 * (0.5 + 0.5 * kotlin.math.sin(t * (5.0 + i * 1.7) + i * 1.3))
            val h = (b.height() * k).toFloat()
            val left = b.left + i * (w + gap)
            canvas.drawRoundRect(left, b.bottom - h, left + w, b.bottom.toFloat(), w / 2, w / 2, paint)
        }
        if (!still) {
            host.removeCallbacks(next)
            host.postDelayed(next, 66)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
