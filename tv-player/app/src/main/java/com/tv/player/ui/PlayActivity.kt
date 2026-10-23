package com.tv.player.ui

import android.app.UiModeManager
import android.content.Context
import android.net.ConnectivityManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tv.player.R
import com.tv.player.core.Cms
import com.tv.player.core.Dlna
import com.tv.player.core.DlnaDevice
import com.tv.player.core.Http
import com.tv.player.core.LineProbe
import com.tv.player.core.Library
import com.tv.player.core.Resolve
import com.tv.player.core.Store
import com.tv.player.data.LiveChannel
import com.tv.player.data.VodDetail
import com.tv.player.data.VodGroup
import com.tv.player.data.VodItem
import com.tv.player.data.toJsonString
import com.tv.player.data.vodItemsFromJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class PlayActivity : BaseActivity() {
    companion object {
        private const val STALL_MS = 20_000L
        private const val SAVE_MS = 10_000L

        fun startVod(context: Context, group: VodGroup) = context.startActivity(
            Intent(context, PlayActivity::class.java)
                .putExtra("key", group.key).putExtra("title", group.title).putExtra("pic", group.pic)
                .putExtra("items", group.items.toJsonString()),
        )

        fun startLive(context: Context, channel: LiveChannel) = context.startActivity(
            Intent(context, PlayActivity::class.java)
                .putExtra("live", true).putExtra("title", channel.name)
                .putStringArrayListExtra("urls", ArrayList(channel.urls)),
        )
    }

    private class Cand(val url: String, val label: String, val src: Int = -1, val line: Int = -1)

    private var isLive = false
    private var title = ""
    private var key = ""
    private var pic = ""
    private var items: List<VodItem> = emptyList()
    private var liveUrls: List<String> = emptyList()
    private val sources by lazy { Store.sources().associateBy { it.key } }

    // Vod state: details are loaded lazily, one source at a time, only when the earlier ones ran out of lines.
    private val details = HashMap<Int, VodDetail?>()
    private var curSrc = 0
    private var curLine = 0
    private var curEp = 0
    private var liveStart = 0

    private val tried = HashSet<String>()
    private var current: Cand? = null
    private var resolvedUrl: String? = null
    private var pendingStart = 0L
    private var started = false
    private var allFailed = false
    private var infoText = ""

    /** Real resolution of the stream that is playing, e.g. "1080P"; remembered per line so the line chips can show it. */
    private var resolution = ""
    private val lineQuality = HashMap<String, String>()

    /** Speed-test results per candidate URL; the line order follows them (smooth first, then sharpest). */
    private val probes = HashMap<String, LineProbe.Result>()
    private var pinned = false // the user picked a line by hand: keep it first
    private var needWarm = false // test the lines before the first start

    private var player: ExoPlayer? = null
    private var playJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stall = Runnable {
        Log.d("TVP", "stalled on ${current?.label}")
        failOver()
    }
    private val saver = object : Runnable {
        override fun run() {
            saveProgress()
            handler.postDelayed(this, SAVE_MS)
        }
    }

    private lateinit var playerView: PlayerView
    private var tvInfo: TextView? = null
    private var favoriteBtn: MaterialButton? = null
    private var linesView: RecyclerView? = null
    private var episodesView: RecyclerView? = null
    private var epLabel: TextView? = null
    private var hud: TextView? = null
    private val lineAdapter = ChipAdapter { selectLine(it) }
    private val epAdapter = ChipAdapter { selectEpisode(it) }

    private val landscape get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private val isTv by lazy { isTv() }

    /** Side-by-side picture + panel on TV, and on wide tablet screens; the stacked layout everywhere else. */
    private fun playLayout() =
        if (isTv || (isTablet() && resources.configuration.screenWidthDp >= 720)) R.layout.activity_play_tv else R.layout.activity_play
    private var panel: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isLive = intent.getBooleanExtra("live", false)
        title = intent.getStringExtra("title").orEmpty()
        if (isLive) {
            liveUrls = intent.getStringArrayListExtra("urls") ?: emptyList()
        } else {
            key = intent.getStringExtra("key").orEmpty()
            pic = intent.getStringExtra("pic").orEmpty()
            items = vodItemsFromJson(intent.getStringExtra("items") ?: "[]")
        }

        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(10_000)
        // One retry instead of the default three, so a dead line hands over to the next one quickly.
        val failFast = object : DefaultLoadErrorHandlingPolicy() {
            override fun getMinimumLoadableRetryCount(dataType: Int) = 1
        }
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(DefaultDataSource.Factory(this, httpFactory)).setLoadErrorHandlingPolicy(failFast),
            )
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also {
                it.addListener(listener)
                it.playWhenReady = true
            }

        setContentView(playLayout())
        bindViews()
        applySystemBars()

        onBackPressedDispatcher.addCallback(this) {
            if (panel?.visibility == View.GONE) {
                panel?.visibility = View.VISIBLE
                playerView.setFullscreenButtonState(false)
            } else if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                finish()
            }
        }

        if (isLive) {
            refreshLists()
            if (liveUrls.isEmpty()) setInfo("这个频道没有可用线路") else {
                needWarm = true
                playCurrent(0)
            }
        } else {
            lifecycleScope.launch { initVod() }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setContentView(playLayout())
        bindViews()
        refreshLists()
        updateFavorite()
        setInfo(infoText)
        applySystemBars()
    }

    private fun bindViews() {
        playerView = findViewById(R.id.player)
        playerView.player = player
        findViewById<TextView?>(R.id.title)?.text = title
        findViewById<View?>(R.id.back)?.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        panel = findViewById(R.id.panel)
        findViewById<View>(R.id.cast).setOnClickListener { showCast() }
        tvInfo = findViewById(R.id.info)
        tvInfo?.setOnClickListener { if (allFailed) playCurrent(0) }
        favoriteBtn = findViewById<MaterialButton?>(R.id.favorite)?.also {
            it.setOnClickListener { toggleFavorite() }
            if (isLive) {
                it.visibility = View.GONE
                (findViewById<View>(R.id.cast).layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart = 0
            }
        }
        linesView = findViewById<RecyclerView?>(R.id.lines)?.also { it.adapter = lineAdapter }
        episodesView = findViewById<RecyclerView?>(R.id.episodes)?.also {
            it.layoutManager = GridLayoutManager(this, if (panel != null) 3 else 4)
            it.adapter = epAdapter
            if (isLive) it.visibility = View.GONE
        }
        epLabel = findViewById<TextView?>(R.id.epLabel)?.also { if (isLive) it.visibility = View.GONE }

        playerView.setFullscreenButtonClickListener { full ->
            if (panel != null) {
                panel?.visibility = if (full) View.GONE else View.VISIBLE
            } else {
                requestedOrientation =
                    if (full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            }
        }
        playerView.setFullscreenButtonState(if (panel != null) panel?.visibility == View.GONE else landscape)
        if (isTv) playerView.requestFocus()
        setupGestures()
    }

    private fun applySystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        // Only the full-screen phone layout hides the system bars; the picture + side panel layout keeps them.
        if (landscape && panel == null) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---- playback ------------------------------------------------------------------------------------------------

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    handler.removeCallbacks(stall)
                    started = true
                    pendingStart = 0
                    setInfo(playingText())
                }
                Player.STATE_ENDED -> if (!isLive) {
                    val count = details[curSrc]?.lines?.getOrNull(curLine)?.episodes?.size ?: 0
                    if (curEp + 1 < count) selectEpisode(curEp + 1)
                }
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.height <= 0) return
            resolution = qualityLabel(videoSize.height)
            lineQuality[lineKey()] = resolution
            if (player?.playbackState == Player.STATE_READY) setInfo(playingText())
            refreshLists()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.d("TVP", "player error ${error.errorCodeName} on ${current?.label}")
            setInfo("这条线路播放失败，正在换下一条…")
            failOver()
        }
    }

    private fun playingText(): String {
        val base = if (isLive) "正在播放：$title · ${current?.label.orEmpty()}" else "正在播放：${epName()} · ${current?.label.orEmpty()}"
        return if (resolution.isEmpty()) base else "$base · $resolution"
    }

    private fun lineKey() = if (isLive) "live:$curLine" else "$curSrc:$curLine"

    private fun qualityLabel(height: Int) = when {
        height >= 2160 -> "4K"
        height >= 1440 -> "2K"
        height >= 1080 -> "1080P"
        height >= 720 -> "720P"
        height >= 480 -> "480P"
        else -> "${height}P"
    }

    private fun playCurrent(startMs: Long) {
        allFailed = false
        tried.clear()
        pendingStart = startMs
        started = false
        attempt()
    }

    /**
     * A single failure can be reported twice (the stall timer, then the player's own timeout error). While the next
     * line is already being picked, the second report must be ignored: restarting would drop the line just picked.
     */
    private fun failOver() {
        if (playJob?.isActive == true) return
        // Dropped mid-playback: carry on from here on the next line instead of starting over.
        if (!isLive && started) pendingStart = player?.currentPosition ?: pendingStart
        attempt()
    }

    /** Picks the next untried line/source and starts it. */
    private fun attempt() {
        playJob?.cancel()
        handler.removeCallbacks(stall)
        player?.stop()
        started = false
        resolution = ""
        playJob = lifecycleScope.launch {
            if (needWarm) {
                needWarm = false
                warmUp()
            }
            while (true) {
                val cand = nextCandidate()
                if (cand == null) {
                    player?.stop()
                    allFailed = true
                    setInfo("所有线路都试过了，没有能播放的。点这里重试，或换一部")
                    return@launch
                }
                setInfo("正在连接：${cand.label}")
                val stream = probes[cand.url]?.takeIf { it.ok }?.stream ?: if (isLive) cand.url else Resolve.stream(cand.url)
                Log.d("TVP", "candidate ${cand.label} ${cand.url} -> $stream")
                if (stream == null) continue
                current = cand
                resolvedUrl = stream
                val media = MediaItem.Builder().setUri(stream).apply {
                    if (stream.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
                }.build()
                player?.apply {
                    // TIME_UNSET lets live streams start at the live edge instead of the oldest segment.
                    setMediaItem(media, if (pendingStart > 0) pendingStart else C.TIME_UNSET)
                    prepare()
                    play()
                }
                handler.postDelayed(stall, STALL_MS)
                return@launch
            }
        }
    }

    private suspend fun nextCandidate(): Cand? {
        if (isLive) {
            val pick = rank(rawCandidates()).firstOrNull { it.url !in tried } ?: return null
            tried += pick.url
            curLine = pick.line
            refreshLists()
            return pick
        }
        while (true) {
            candidatesAt(curEp).firstOrNull { it.url !in tried }?.let {
                tried += it.url
                curSrc = it.src
                curLine = it.line
                refreshLists()
                return it
            }
            val next = items.indices.firstOrNull { !details.containsKey(it) } ?: return null
            details[next] = loadDetail(next)
        }
    }

    private fun candidatesAt(ep: Int): List<Cand> = rank(rawVodCandidates(ep))

    private fun rawCandidates(): List<Cand> = if (isLive) {
        val n = liveUrls.size
        (0 until n).map { k ->
            val idx = (liveStart + k) % n
            Cand(liveUrls[idx], "线路 ${idx + 1}/$n", 0, idx)
        }
    } else {
        rawVodCandidates(curEp)
    }

    private fun smartOn(): Boolean = when (Store.lineMode()) {
        "order" -> false
        "smart_always" -> true
        else -> !(getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    }

    /**
     * Smooth lines first, the sharpest of them leading; if none is smooth, the fastest; lines that failed the test
     * last. Lines with no test result keep their listed order after the tested ones. A hand-picked line stays first.
     */
    private fun rank(list: List<Cand>): List<Cand> {
        if (!smartOn() || probes.isEmpty()) return list
        val head = if (pinned) list.take(1) else emptyList()
        fun group(c: Cand): Int {
            val p = probes[c.url] ?: return 2
            return if (!p.ok) 3 else if (p.smooth) 0 else 1
        }
        return head + list.drop(head.size).sortedWith(
            compareBy<Cand>(
                { group(it) },
                { if (group(it) == 0) -(probes[it.url]?.height ?: 0) else 0 },
                { -(probes[it.url]?.mbps ?: 0.0) },
            ),
        )
    }

    /**
     * Tests the candidate lines in parallel. Waits for all of them, but leaves early once a smooth line is known
     * (after 1.5 s) and never longer than 4 s; slow ones keep going in the background and still feed the ranking.
     */
    private suspend fun warmUp() {
        if (!smartOn() || pinned) return
        setInfo("正在测速选线…")
        if (!isLive) {
            // A wider choice: bring in the lines of up to two more sources, in parallel.
            val extra = items.indices.filter { !details.containsKey(it) }.take(2)
            if (extra.isNotEmpty()) {
                coroutineScope {
                    extra.map { i -> async { i to withTimeoutOrNull(3_000) { loadDetail(i) } } }.awaitAll()
                }.forEach { (i, d) -> if (d != null) details[i] = d }
            }
        }
        val cap = minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        val fresh = rawCandidates().filter { it.url !in probes }.take(6)
        if (fresh.size < 2) return
        val jobs = fresh.map { c ->
            lifecycleScope.launch {
                val result = LineProbe.probe(c.url, cap)
                probes[c.url] = result
                Log.d("TVP", "probe ${c.label}: $result")
            }
        }
        val t0 = System.currentTimeMillis()
        while (jobs.any { it.isActive }) {
            val waited = System.currentTimeMillis() - t0
            if (waited >= 4_000) break
            if (waited >= 1_500 && fresh.any { probes[it.url]?.smooth == true }) break
            delay(100)
        }
    }

    /** Same episode number in every loaded source: the chosen source first, starting at the chosen line. */
    private fun rawVodCandidates(ep: Int): List<Cand> = buildList {
        val order = listOf(curSrc) + details.keys.filter { it != curSrc }.sorted()
        for (s in order) {
            val lines = details[s]?.lines ?: continue
            val start = if (s == curSrc) curLine else 0
            for (k in lines.indices) {
                val li = (start + k) % lines.size
                lines[li].episodes.getOrNull(ep)?.let { add(Cand(it.url, "${items[s].sourceName} · ${lines[li].name}", s, li)) }
            }
        }
    }

    private suspend fun loadDetail(index: Int): VodDetail? {
        val item = items[index]
        val source = sources[item.sourceKey] ?: return null
        return try {
            withTimeoutOrNull(12_000) { Cms.detail(source, item.id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun initVod() {
        setInfo("正在加载线路…")
        val saved = Library.progress(key)
        for (i in items.indices) {
            val d = loadDetail(i)
            details[i] = d
            if (d != null) {
                curSrc = i
                break
            }
        }
        val first = details[curSrc]
        if (first == null) {
            setInfo("没有可用的播放线路，换个关键词重新搜")
            return
        }
        var start = 0L
        if (saved != null) {
            curEp = saved.ep.coerceIn(0, maxOf(0, (first.lines.firstOrNull()?.episodes?.size ?: 1) - 1))
            start = saved.posMs
        }
        refreshLists()
        updateFavorite()
        needWarm = true
        playCurrent(start)
    }

    private fun epName(): String = details[curSrc]?.lines?.getOrNull(curLine)?.episodes?.getOrNull(curEp)?.name.orEmpty()

    private fun selectLine(index: Int) {
        if (index < 0) return
        pinned = true
        if (isLive) {
            liveStart = index
            curLine = index
        } else {
            val d = details[curSrc] ?: return
            curLine = index
            curEp = curEp.coerceAtMost(maxOf(0, (d.lines.getOrNull(index)?.episodes?.size ?: 1) - 1))
        }
        refreshLists()
        playCurrent(0)
    }

    private fun selectEpisode(index: Int) {
        if (index < 0) return
        curEp = index
        refreshLists()
        playCurrent(0)
    }

    private fun refreshLists() {
        if (isLive) {
            lineAdapter.submit(liveUrls.indices.map { "线路${it + 1}" + (lineQuality["live:$it"]?.let { q -> " $q" } ?: "") }, curLine)
            return
        }
        val d = details[curSrc] ?: return
        val names = d.lines.map { it.name }
        lineAdapter.submit(
            names.mapIndexed { i, n ->
                val name = if (names.count { it == n } > 1) "$n ${i + 1}" else n
                lineQuality["$curSrc:$i"]?.let { q -> "$name · $q" } ?: name
            },
            curLine,
        )
        val episodes = d.lines.getOrNull(curLine)?.episodes ?: emptyList()
        epAdapter.submit(episodes.map { it.name }, curEp)
        epLabel?.text = "选集（共 ${episodes.size} 集）"
        episodesView?.scrollToPosition(curEp)
    }

    private fun setInfo(text: String) {
        infoText = text
        tvInfo?.text = text
    }

    private fun saveProgress() {
        if (isLive || !started || key.isEmpty()) return
        val p = player ?: return
        val pos = p.currentPosition
        val nearEnd = p.duration > 0 && pos > p.duration - 15_000
        Library.saveProgress(Library.Entry(key, title, pic, items, curEp, epName(), if (nearEnd) 0 else pos))
    }

    // ---- favorites -----------------------------------------------------------------------------------------------

    private fun toggleFavorite() {
        Library.setFavorite(Library.Entry(key, title, pic, items), !Library.isFavorite(key))
        updateFavorite()
    }

    private fun updateFavorite() {
        val button = favoriteBtn ?: return
        if (isLive) return
        val fav = Library.isFavorite(key)
        button.text = getString(if (fav) R.string.favorited else R.string.favorite)
        button.setIconResource(if (fav) R.drawable.ic_heart else R.drawable.ic_heart_outline)
        button.iconTint = ColorStateList.valueOf(getColor(if (fav) R.color.heart else R.color.on_surface))
    }

    // ---- DLNA casting --------------------------------------------------------------------------------------------

    private fun showCast() {
        val url = resolvedUrl
        if (url == null) {
            toast("视频还没开始加载，没有可投的地址")
            return
        }
        var searching: Job? = null
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("正在搜索设备…")
            .setMessage("手机和电视需要连同一个 Wi-Fi")
            .setNegativeButton("取消") { _, _ -> searching?.cancel() }
            .setOnCancelListener { searching?.cancel() }
            .show()
        searching = lifecycleScope.launch {
            val devices = try {
                Dlna.discover(this@PlayActivity)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            dialog.dismiss()
            val names = devices.map { it.name } + "手动输入设备地址…"
            MaterialAlertDialogBuilder(this@PlayActivity)
                .setTitle(if (devices.isEmpty()) "没找到设备：确认同一 Wi-Fi 且电视开了 DLNA 接收，或手动输入" else "选择投屏设备")
                .setItems(names.toTypedArray()) { _, i -> if (i < devices.size) castTo(devices[i], url) else askDeviceAddress(url) }
                .show()
        }
    }

    private fun askDeviceAddress(url: String) {
        val input = EditText(this).apply {
            hint = "http://192.168.1.20:49152/description.xml"
            setSingleLine()
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("设备描述地址")
            .setMessage("路由器屏蔽组播时用。电视的投屏 App 里一般能看到，形如 http://IP:端口/xxx.xml")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("投屏") { _, _ ->
                lifecycleScope.launch {
                    val device = Dlna.describe(input.text.toString().trim())
                    if (device == null) toast("这个地址不是有效的 DLNA 设备描述") else castTo(device, url)
                }
            }
            .show()
    }

    private fun castTo(device: DlnaDevice, url: String) {
        lifecycleScope.launch {
            val error = Dlna.cast(device, url, title)
            if (error == null) {
                player?.pause()
                toast("已投屏到 ${device.name}")
            } else {
                toast("投屏失败：$error")
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    // ---- remote control (TV) -------------------------------------------------------------------------------------

    private val hideHud = Runnable { hud?.visibility = View.GONE }

    private fun flashHud(text: String) {
        hud?.apply {
            this.text = text
            visibility = View.VISIBLE
        }
        handler.removeCallbacks(hideHud)
        handler.postDelayed(hideHud, 900)
    }

    /** With the picture focused and the controller hidden: left/right seek 10s, up/down jump to the side panel. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isTv && event.action == KeyEvent.ACTION_DOWN && playerView.hasFocus() && !playerView.isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (!isLive) {
                    player?.let { p ->
                        val delta = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 10_000L else -10_000L
                        val target = (p.currentPosition + delta).coerceIn(0L, maxOf(0L, p.duration))
                        p.seekTo(target)
                        playerView.hideController()
                        flashHud(if (delta > 0) "快进 10 秒  ${formatTime(target)}" else "快退 10 秒  ${formatTime(target)}")
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (panel?.visibility == View.VISIBLE) {
                    val first = favoriteBtn?.takeIf { it.visibility == View.VISIBLE } ?: findViewById(R.id.cast)
                    first.requestFocus()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---- gestures: swipe to seek / brightness / volume, double tap to pause, long press for 2x ------------------

    private fun setupGestures() {
        val view = playerView
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val label = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = ColorDrawable(0xAA000000.toInt())
        }
        view.overlayFrameLayout?.addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        hud = label

        var mode = 0 // 0 undecided, 1 seek, 2 brightness, 3 volume
        var startPos = 0L
        var seekTarget = 0L
        var startBrightness = 0f
        var startVolume = 0
        var speedUp = false

        val detector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (view.isControllerFullyVisible) view.hideController() else view.showController()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    player?.let { if (it.isPlaying) it.pause() else it.play() }
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    if (isLive) return
                    player?.setPlaybackSpeed(2f)
                    speedUp = true
                    label.text = "2× 倍速"
                    label.visibility = View.VISIBLE
                }

                override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                    if (e1 == null) return false
                    val p = player ?: return false
                    if (mode == 0) {
                        if (abs(e2.x - e1.x) > abs(e2.y - e1.y)) {
                            if (isLive || p.duration <= 0) return false
                            mode = 1
                            startPos = p.currentPosition
                        } else if (e1.x < view.width / 2) {
                            mode = 2
                            startBrightness = currentBrightness()
                        } else {
                            mode = 3
                            startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                        }
                    }
                    view.hideController()
                    val dx = e2.x - e1.x
                    val up = e1.y - e2.y
                    when (mode) {
                        1 -> {
                            seekTarget = (startPos + (dx / view.width * 90_000).toLong()).coerceIn(0, p.duration)
                            label.text = "${formatTime(seekTarget)} / ${formatTime(p.duration)}"
                        }
                        2 -> {
                            val b = (startBrightness + up / view.height * 1.2f).coerceIn(0.02f, 1f)
                            window.attributes = window.attributes.also { it.screenBrightness = b }
                            label.text = "亮度 ${(b * 100).roundToInt()}%"
                        }
                        3 -> {
                            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val v = (startVolume + up / view.height * max * 1.2f).roundToInt().coerceIn(0, max)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                            label.text = "音量 $v/$max"
                        }
                    }
                    label.visibility = View.VISIBLE
                    return true
                }
            },
        )

        view.setOnTouchListener { v, event ->
            detector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (mode == 1 && event.actionMasked == MotionEvent.ACTION_UP) player?.seekTo(seekTarget)
                if (speedUp) {
                    player?.setPlaybackSpeed(1f)
                    speedUp = false
                }
                mode = 0
                label.visibility = View.GONE
                if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            }
            true
        }
    }

    private fun currentBrightness(): Float {
        val set = window.attributes.screenBrightness
        if (set >= 0) return set
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Exception) {
            0.5f
        }
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    // ---- lifecycle -----------------------------------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        handler.postDelayed(saver, SAVE_MS)
        updateFavorite()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(saver)
        saveProgress()
        player?.pause()
    }

    override fun onDestroy() {
        playJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }
}
