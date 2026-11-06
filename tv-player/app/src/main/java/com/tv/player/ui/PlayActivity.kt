package com.tv.player.ui

import android.Manifest
import android.app.PictureInPictureParams
import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.animation.ValueAnimator
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.animation.DecelerateInterpolator
import android.widget.ProgressBar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.VelocityTracker
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.animation.doOnCancel
import androidx.core.animation.doOnEnd
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.PreloadException
import androidx.media3.exoplayer.source.preload.PreloadManagerListener
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDragHandleView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import coil.load
import com.tv.player.R
import com.tv.player.core.Cms
import com.tv.player.core.CatchupUrl
import com.tv.player.core.Dlna
import com.tv.player.core.Epg
import com.tv.player.core.DlnaDevice
import com.tv.player.core.Http
import com.tv.player.core.LineProbe
import com.tv.player.core.NetStore
import com.tv.player.core.Library
import com.tv.player.core.Quark
import com.tv.player.core.QuarkWeb
import com.tv.player.core.Resolve
import com.tv.player.core.CachePolicy
import com.tv.player.core.Downloads
import com.tv.player.core.FallbackLatch
import com.tv.player.core.FrameRateMatch
import com.tv.player.core.PrefetchPolicy
import com.tv.player.core.PrefetchScheduler
import com.tv.player.core.PlayCache
import com.tv.player.core.PlayLog
import com.tv.player.core.PlayNetStats
import com.tv.player.core.SeekRamp
import com.tv.player.core.StallPolicy
import com.tv.player.core.Store
import com.tv.player.core.Subtitles
import com.tv.player.core.Tracker
import com.tv.player.data.Catchup
import com.tv.player.data.LiveChannel
import com.tv.player.data.VodDetail
import com.tv.player.data.VodGroup
import com.tv.player.data.VodItem
import com.tv.player.data.toJsonString
import com.tv.player.data.vodItemsFromJson
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
class PlayActivity : BaseActivity() {
    companion object {
        private const val STALL_MS = 20_000L
        private const val TALL_BOX = 0.5f // 竖屏视频的小窗最高占页面的这么多
        private const val FLICK_DP = 1000 // 每秒这么多 dp 以上算「甩」
        private const val NEXT_CARD_MAX_MS = 6_000L // 下一集的卡片最多盖这么久
        private const val RETRY_DELAY_MS = 2_000L
        private const val RETRY_WINDOW_MS = 60_000L // 原线路重试过后这么久之内又出错，就换线
        private const val DROP_LOG_MS = 60_000L // 掉帧最多这么久记一条
        private const val MAX_BUFFER_MS = 50_000
        private const val SAVE_MS = 10_000L
        private const val RESUME_TIP_MIN_MS = 10_000L // 续播位置不到这么多就不提示「已从 xx:xx 继续播放」
        private const val RESUME_TIP_MS = 6_000L // 续播提示停留这么久
        private const val LOCK_SHOW_MS = 2_500L // 锁屏后点一下，锁键露出这么久
        private const val TAP_SEEK_STEP_MS = 10_000L // 双击两侧一下跳这么多
        private const val TAP_SEEK_WINDOW_MS = 700L // 双击过后这么久之内同一侧单点也接着跳
        private const val TAP_SEEK_EDGE = 0.3f // 画面左右各这么宽算「两侧」，中间双击还是暂停
        private const val MAX_SKIP = 300
        private const val NEXT_CARD_MS = 10_000L // 离一集结束还有这么久时提示下一集
        private const val NEXT_CARD_MIN_MS = 5 * 60_000L // 短于这个的（短剧）照旧直接连播，不弹卡片
        private const val SUB_STEP_MS = 500L
        private const val MAX_SUB_OFFSET_MS = 300_000L
        private val SUB_SCALES = listOf(0.8f, 1f, 1.3f, 1.6f)
        private const val EP_GROUP = 30
        private const val RESCUE_MS = 4_000L
        private const val PRELOAD_AFTER_MS = 2_500L // how long an episode plays before its neighbours are loaded ahead
        private const val PRELOAD_MS = 5_000L // how much of the next episode is loaded ahead
        private const val PRELOAD_MIN_BUFFER_MS = 20_000L // 当前这集至少缓冲这么多，才预加载后面几集
        private const val PRELOAD_RETRY_MS = 2_000L
        private const val BACKGROUND_PROBE_MS = 10_000L
        private const val QUICK_MS = 6 * 3600_000L
        private const val MAX_SHOWS = 200 // programmes listed for replay
        private const val LINES_FOLD = 4 // more lines than this get the "全部 N 条" row; fewer fit in the row anyway
        private val OK_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
        private val DIRECT = Regex("\\.(m3u8|mp4|flv|mkv)(\\?|$)", RegexOption.IGNORE_CASE)

        /** [startEp] >= 0 opens that episode (a file picked in the drive browser) instead of the one last watched. */
        fun startVod(context: Context, group: VodGroup, startEp: Int = -1) = context.startActivity(
            Intent(context, PlayActivity::class.java)
                .putExtra("key", group.key).putExtra("title", group.title).putExtra("pic", group.pic)
                .putExtra("items", group.items.toJsonString()).putExtra("ep", startEp),
        )

        fun startLive(context: Context, channel: LiveChannel) = context.startActivity(
            Intent(context, PlayActivity::class.java)
                .putExtra("live", true).putExtra("title", channel.name).putExtra("group", channel.group)
                .putStringArrayListExtra("urls", ArrayList(channel.urls))
                .putExtra("catchup_template", channel.catchup?.template.orEmpty())
                .putExtra("catchup_days", channel.catchup?.days ?: 0),
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

    // Vod state: details of every source load in the background; the line list shows the lines of all of them.
    private val details = HashMap<Int, VodDetail?>()
    private var curSrc = 0
    private var curLine = 0
    private var lineRefs: List<Pair<Int, Int>> = emptyList() // the line list's rows: (source index, line index)
    private var curEp = 0
    private var startEp = -1
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
    private var probeJobs: List<Job> = emptyList() // speed tests still running; cancelled when the channel changes
    private var needWarm = false // speed-test the lines if the first one is slow to start

    /**
     * The first line starts at once; only if it has not started after [RESCUE_MS] are the other lines speed-tested
     * (in the background) and, when a smooth one is found, played instead. A good first line therefore costs nothing.
     */
    /**
     * Once a title has played smoothly for a while, the other lines are tested quietly in the background, so that if
     * this one stalls later the failover goes straight to a line known to be good (and share pages are pre-resolved).
     */
    private val backgroundProbe = Runnable {
        if (isLive || !started || !smartOn() || pinned) return@Runnable
        lifecycleScope.launch { warmUp(background = true) }
    }

    private var quickFirst: Pair<Cand, String?>? = null // continue watching: the line to start with, already resolved

    private var gen = 0 // bumped by every new start (episode, line, channel); stale background work checks it
    private val rescue = Runnable {
        if (started || !smartOn() || pinned) return@Runnable
        // Already receiving video, just a slow server: speed tests now would only take its bandwidth (the stall
        // timer still catches a line that stops delivering).
        if ((player?.totalBufferedDuration ?: 0L) > 0L) return@Runnable
        val waitingOn = current?.url
        val g = gen
        lifecycleScope.launch {
            warmUp()
            if (g != gen || started || pinned || current?.url != waitingOn) return@launch
            val best = (if (isLive) rank(rawCandidates()) else candidatesAt(curEp)).firstOrNull { it.url !in tried }
            if (best != null && probes[best.url]?.smooth == true) {
                Log.d("TVP", "slow start on ${current?.label}, switching to ${best.label}")
                attempt()
            }
        }
    }

    // Live channel zapping: the channels of the group the current one was opened from.
    private var liveGroup = ""
    private var groupChannels: List<LiveChannel> = emptyList()
    private var digits = ""

    // Skip intro / outro, speed, sleep timer, leaving the app.
    private var introDone = false

    // Subtitle file the user imported (a UTF-8 copy in the cache), the picture fit, and the programme replay of a live channel.
    private var extSubtitle: Uri? = null
    private var extSubtitleMime: String? = null
    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::importSubtitle) }
    private var catchup: Catchup? = null // the channel's playback addresses for past programmes, if it has any
    private var replayMode = false // a past programme is playing in place of the live picture; "回到直播" leaves it
    private var outroDone = false
    private var speedBoost = false

    // 遥控器按住左右键：按住期间只挪目标位置，松手再跳一次（见 SeekRamp）；-1 表示没在按住
    private var seekPending = -1L
    private var seekFrom = 0L
    private var seekLastAt = 0L
    private var seekDownAt = -1L // 这次按住的 downTime：第一下按键被系统吃掉（如刚退出触摸模式）时据此补上起点
    private val commitSeek = Runnable { finishHeldSeek() }

    // 一集快放完时的「下一集」提示卡片；点了取消，这一集放完就不自动连播
    private var nextCard: View? = null
    private var nextCancelled = false

    // 导入字幕的原文件，和现在的时间偏移（毫秒，正数＝字幕晚出现）
    private var extSubtitleBase: Uri? = null
    private var subOffsetMs = 0L
    private val applySubOffset = Runnable { shiftSubtitle() }

    // 片源没写帧率时，从解码出来的帧时间戳估（播放线程上喂，见 FrameRateMatch.Estimator）
    private val frameEstimator = FrameRateMatch.Estimator()

    // Stalls in this viewing: the more there were, the longer playback gathers buffer before resuming (see StallPolicy).
    private val stallPolicy = StallPolicy()

    // 播放日志（PlayLog）：播放器请求的耗时，卡顿开始的时刻和当时的情况，这次打开以来的播放时长、卡顿和换线次数
    private val netStats = PlayNetStats()
    private var stallAt = 0L
    private var stallNote: String? = null
    private var stallTotalMs = 0L
    private var stallCount = 0
    private var failOvers = 0
    private var playingSince = 0L
    private var playedMs = 0L

    // 画面这一侧：解码器、掉帧（分得清是网络卡还是解码卡），去广告剔掉的分片，原线路重试
    private var videoDecoder = ""
    private var audioDecoder = ""
    private var droppedFrames = 0L
    private var renderedFrames = 0L
    private var dropLoggedAt = 0L
    private var droppedSinceLog = 0
    private val adCleaned = HashMap<String, Int>() // 播放列表地址 -> 剔除的分片数（重新加载同一份不重复算）
    private var adRule = ""
    private var retries = 0
    private var retriedAt = 0L

    // Fetching ahead / reading in pieces (on demand only). prefetchRoot is the address of what the player has now: only
    // that is fetched ahead for, not an episode loaded ahead of it.
    private val fallbackLatch = FallbackLatch()
    private val prefetchPool = Executors.newCachedThreadPool { r -> Thread(r, "tvp-prefetch").apply { isDaemon = true } }
    private var prefetch: PrefetchScheduler? = null

    @Volatile
    private var prefetchRoot: String? = null
    private var stalledThisEpisode = false
    private var lastSeekAt = 0L

    /** Something other than the network is about to make the player buffer (a seek, a track change): not a stall. */
    private fun noteDisturbance() {
        lastSeekAt = SystemClock.elapsedRealtime()
    }

    // Next-episode preloading (on demand video only).
    private class Preloaded(val src: Int, val line: Int, val stream: String, val subtitle: android.net.Uri?, val item: MediaItem)

    private var preloadManager: DefaultPreloadManager? = null
    private var controlPlayer: Player? = null // what the picture's controller drives: the player, with episodes for next / previous
    private var preloadEpisodes: Set<Int> = emptySet() // the episodes the manager is asked to load ahead
    private val preloaded = HashMap<Int, Preloaded>() // by episode number, not yet taken by the player
    private val retired = ArrayList<MediaItem>() // taken by the player: let go of them once it has moved on
    private var playingPreload: MediaItem? = null
    private var preloadJob: Job? = null
    // 当前这集缓冲够了才去预加载后面几集：慢线路上刚起播就预加载，会和正在放的这集抢带宽（播放日志里看到过
    // 起播 2.5 s 后开始预加载、紧接着就卡）。不够就过一会儿再看；卡过之后再次就绪时会重新排上。
    // 「够了」：缓冲到 20 s，或缓冲到了片尾，或播放器自己停止下载了（缓冲已满：码率很高的片子先碰到字节上限，
    // 可能永远缓冲不到 20 s）。
    private val preloadNext = object : Runnable {
        override fun run() {
            val p = player ?: return
            val toEnd = p.duration != C.TIME_UNSET && p.bufferedPosition >= p.duration - 1_000
            val full = p.playbackState == Player.STATE_READY && !p.isLoading
            if (p.totalBufferedDuration >= PRELOAD_MIN_BUFFER_MS || toEnd || full) preloadNextEpisode()
            else handler.postDelayed(this, PRELOAD_RETRY_MS)
        }
    }
    private var sleepAtMs = 0L // SystemClock.elapsedRealtime() deadline; 0 = off
    private var sleepAfterEp = false
    private var sleepChoice = 0
    private var pipRequested = false
    private var videoOff = false
    private var keepAlive = false // the background-sound service was started and must be stopped again
    private var leaving = false // Home / recents was pressed, as opposed to a dialog covering the screen
    private var tick = 0

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
    private var hud: GestureHud? = null
    private val lineAdapter = ChipAdapter { onLineClick(it) }
    private var lineCount = 0 // the rows that are lines; the row after them opens the panel listing all of them
    private var lineLabels: List<String> = emptyList()
    private var lineSelected = -1
    /** TV: episodes are shown 30 at a time; [epOffset] is the first episode of the shown range. */
    private var epOffset = 0
    private var epGroup = -1 // the range the user picked; -1 = follow the playing episode
    private val groupAdapter = ChipAdapter { epGroup = it; refreshLists() }
    private val epAdapter = ChipAdapter(fill = true) {
        val ep = epOffset + it
        // The episode already playing in the small window: keep its position, just go full screen.
        if (!(isTv && !isLive && !tvFull && ep == curEp && started)) selectEpisode(ep)
        if (isTv && !isLive && !tvFull) setTvFull(true) // like a TV box: picking an episode starts it full screen
    }

    private val landscape get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private val isTv by lazy { isTv() }

    /**
     * TV: full-screen picture with a control bar over the bottom. Wide tablets: picture + side panel. Everywhere else
     * the stacked phone layout.
     */
    private fun playLayout() = when {
        isTv && !isLive && !tvFull -> R.layout.activity_play_tvdetail
        isTv -> R.layout.activity_play_overlay
        isTablet() && resources.configuration.screenWidthDp >= 720 -> R.layout.activity_play_tv
        else -> R.layout.activity_play
    }

    /** The phone stack (small picture over the lists); wide tablets have a side panel instead, and TV its own pages. */
    private val phoneStack get() = !isTv && !(isTablet() && resources.configuration.screenWidthDp >= 720)
    /** 手机横屏全屏（layout-land 的 activity_play）：面板从右边滑出 */
    private val phoneLand get() = phoneStack && landscape
    /** 横屏的手机和平板：倍速、选集、播放设置从右边滑出（底部面板在横屏里又矮又挡画面） */
    private val drawers get() = !isTv && landscape

    /**
     * 手机上的竖屏视频（短剧）：画面铺满整个屏幕，和 B 站的竖屏视频一样。往下拖小窗下面那一栏进入，右下角的箭头
     * （或返回键）退出。[portraitVideo] 表示正在放的画面是竖的。
     */
    private var portraitFull = false
    /** From the controller's own visibility callback: isControllerFullyVisible keeps saying true once the controller has timed out. */
    private var controllerShown = false
    private var portraitVideo = false
    private var vfullTipShown = false

    /**
     * 换集、换线路时不自动弹控制栏：attempt 里的 stop() 让播放器回到 IDLE，PlayerView 会当成「该一直显示」把控制栏
     * 弹出来，起播后再按超时收起，每换一集都闪一次（竖屏短剧一页页刷时最明显）。起播（或全部失败）后恢复。
     */
    private var quietControls = false

    private fun setQuietControls(on: Boolean) {
        quietControls = on
        if (::playerView.isInitialized) playerView.controllerAutoShow = !on
    }

    /** 手机横屏全屏锁屏（腾讯视频、B 站都有）：锁住后手势、控制栏都不响应，点画面只露出锁键；转回竖屏自动解锁 */
    private var locked = false
    private var orientationBeforeLock = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private val hideLock = Runnable { if (locked) findViewById<View?>(R.id.lock)?.let { fadeTo(it, false) } }
    /** 横屏控制栏右下角的「倍速」「选集」（插在 media3 自己的按钮前面） */
    private var speedBarBtn: TextView? = null
    private var episodeBarBtn: TextView? = null
    /** 从观看记录接着播的位置：第一次起播时左下角提示「已从 xx:xx 继续播放 · 从头播放」，提示过就清零 */
    private var resumeTipFrom = 0L
    private var resumeTip: View? = null
    private val hideResumeTip = Runnable { resumeTip?.let { fadeTo(it, false, gone = true) } }
    private val vfullShown get() = portraitFull && phoneStack && !landscape
    private val canPortraitFull get() = phoneStack && !isLive && !landscape && !isInPictureInPictureMode

    /** The TV control bar over the picture (shown on demand, hides by itself). */
    private val overlay get() = isTv && (isLive || tvFull)

    /** TV, on demand: false = the details page (small picture), true = full screen with the control bar. */
    private var tvFull = false
    private var progressText: TextView? = null
    private var playPauseBtn: MaterialButton? = null
    private val hidePanel = Runnable { setPanelVisible(false) }
    private var panel: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isLive = intent.getBooleanExtra("live", false)
        title = intent.getStringExtra("title").orEmpty()
        startEp = intent.getIntExtra("ep", -1)
        if (isLive) {
            liveUrls = intent.getStringArrayListExtra("urls") ?: emptyList()
            liveGroup = intent.getStringExtra("group").orEmpty()
            catchup = intent.getStringExtra("catchup_template")?.takeIf { it.isNotEmpty() }
                ?.let { Catchup(it, intent.getIntExtra("catchup_days", 0)) }
        } else {
            key = intent.getStringExtra("key").orEmpty()
            pic = intent.getStringExtra("pic").orEmpty()
            items = vodItemsFromJson(intent.getStringExtra("items") ?: "[]")
        }

        // 走 OkHttp：和测速、解析共用连接池（测速时连上的服务器直接复用连接）、解析缓存和 IPv4 优先，
        // 超时（建连 8 s、读 10 s）和跨协议跳转都按 Http.client 的设置。
        val httpFactory = OkHttpDataSource.Factory(Http.playerClient(netStats))
            // As a default property (not setUserAgent, which is applied last and would replace a per-request one).
            .setDefaultRequestProperties(mapOf("User-Agent" to Http.UA))
        // One retry instead of the default three, so a dead line hands over to the next one quickly.
        val failFast = object : DefaultLoadErrorHandlingPolicy() {
            override fun getMinimumLoadableRetryCount(dataType: Int) = 1
        }
        // Start after 1 s of video instead of the default 2.5 s; the rest keeps buffering while it plays.
        // 不计费的网络上一直缓冲到 50 s（下限 = 上限）：不再缓满就停、掉到 15 s 才接着下，连接不会闲着被服务器断开，
        // 线路忽快忽慢时余量也多。计费网络（手机流量）还是 15 s 才接着下，中途退出少浪费流量。
        val metered = (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
        val loadControl = StallAwareLoadControl(
            DefaultLoadControl.Builder().setBufferDurationsMs(if (metered) 15_000 else MAX_BUFFER_MS, MAX_BUFFER_MS, 1_000, 2_000).build(),
            stallPolicy,
        )
        // Drive addresses (web login) need the session cookies on every request to the drive's hosts.
        // 网络存储：SMB 文件走自己的数据源，WebDAV 文件的请求带上账号
        val resolving = ResolvingDataSource.Factory(SmbAwareDataSource.Factory(DefaultDataSource.Factory(this, httpFactory))) { spec ->
            val extra = spec.uri.host?.let(QuarkWeb::headersFor) ?: NetStore.headersFor(spec.uri.toString())
            if (extra == null) spec else spec.withRequestHeaders(spec.httpRequestHeaders + extra)
        }
        // 点播 m3u8 按订阅规则去广告（见 AdFilter）：放在最底层，缓存、预取、预加载拿到的都是清理后的列表
        val network = AdFilterDataSource.Factory(resolving, Store::adRules) { url, r ->
            Log.d("TVP", "ad filter (${r.rule}): ${r.removed} segments removed")
            handler.post {
                adCleaned[url] = r.removed
                adRule = r.rule
            }
        }
        // On-demand video is cached on disk as it plays (and as the next episode is loaded ahead); live is not, and neither
        // are a drive's files: their addresses expire and need the login.
        // A drive's files (either kind of login) are left alone as a whole: one film would fill the cache on its own.
        val fromDrive = !isLive && items.any { it.sourceKey == Quark.SOURCE_KEY || it.sourceKey == QuarkWeb.SOURCE_KEY || it.sourceKey == NetStore.SOURCE_KEY }
        val bypass = { uri: Uri -> !CachePolicy.cacheable(uri.scheme, uri.path) || uri.host?.let(QuarkWeb::headersFor) != null }
        val dataSource: DataSource.Factory = if (isLive || fromDrive) network else {
            val cache = PlayCache.get(this)
            // Lines that limit each connection: the next segments are fetched ahead over other connections (an HLS stream),
            // or a video file is read in pieces over several (see PrefetchScheduler and RangedDataSource). Off on a metered
            // network, as the line tests are.
            val prefetching = Store.parallelPrefetch()
            val allowed = { root: String -> prefetchOn() && root == prefetchRoot }
            val scheduler = if (prefetching) PrefetchScheduler(
                fetcher = CacheSegmentFetcher(cache, network),
                executor = prefetchPool,
                latch = fallbackLatch,
                allowed = allowed,
                exclude = { url -> bypass(Uri.parse(url)) },
                log = { Log.d("TVP", "prefetch: $it") },
            ).also { prefetch = it } else null
            val fileSource: DataSource.Factory = if (!prefetching) network else RangedDataSource.Factory(
                upstream = network,
                eligible = { uri -> !bypass(uri) && PrefetchPolicy.rangedFile(uri.path) && allowed(uri.toString()) },
                latch = fallbackLatch,
                executor = prefetchPool,
                log = { Log.d("TVP", "prefetch: $it") },
            )
            // A cache that fails must not fail the playback.
            val cacheFlags = CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
            val routed = RoutingDataSource.Factory(
                direct = network,
                // Segments: one being fetched ahead is waited for, not downloaded a second time on another connection.
                cached = CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(network)
                    .setFlags(cacheFlags or (if (prefetching) CacheDataSource.FLAG_BLOCK_ON_CACHE else 0)),
                bypass = bypass,
                // Whole video files: read in pieces below the cache, nothing is written to it from the side, so they
                // keep the plain, never-waiting behaviour.
                fileCached = if (prefetching) CacheDataSource.Factory()
                    .setCache(cache)
                    .setUpstreamDataSourceFactory(fileSource)
                    .setFlags(cacheFlags) else null,
                // Only the file being played: an HLS stream's segments may be called .mp4 too, and keep waiting for fetches ahead.
                isFile = { uri -> PrefetchPolicy.rangedFile(uri.path) && uri.toString() == prefetchRoot },
            )
            if (scheduler != null) PrefetchingDataSource.Factory(routed, scheduler) else routed
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSource).setLoadErrorHandlingPolicy(failFast)
        // The player is built by the preload manager's builder so both share one pipeline: what the manager loads ahead
        // of the next episode (its address, its playlist, the first seconds) is what the player then plays.
        val preloadBuilder = DefaultPreloadManager.Builder(
            this,
            TargetPreloadStatusControl<Int> { episode ->
                if (episode in preloadEpisodes) DefaultPreloadManager.Status(DefaultPreloadManager.Status.STAGE_LOADED_FOR_DURATION_MS, PRELOAD_MS) else null
            },
        )
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            // 视频解码器起不来时换一个；没有 AC3 等音频硬解时用 ffmpeg 软解（见 AudioFallbackRenderersFactory）
            .setRenderersFactory(AudioFallbackRenderersFactory(this))
        player = preloadBuilder
            .buildExoPlayer(
                ExoPlayer.Builder(this)
                    .setAudioAttributes(AudioAttributes.DEFAULT, true)
                    .setHandleAudioBecomingNoisy(true)
                    // The controller's back / forward buttons: 10 s each way, like the remote's keys and the swipe (not 5 and 15).
                    .setSeekBackIncrementMs(10_000)
                    .setSeekForwardIncrementMs(10_000),
            )
            .also {
                it.addListener(listener)
                it.addAnalyticsListener(analytics)
                // 隧道播放（电视上、设置里开了才用）：设备或音轨不支持时播放器自己退回普通方式
                tunnelRequested = isTv && Store.tunneling()
                if (tunnelRequested) (it.trackSelector as? DefaultTrackSelector)?.let { ts ->
                    ts.setParameters(ts.buildUponParameters().setTunnelingEnabled(true))
                }
                it.playWhenReady = true
                it.setPlaybackSpeed(if (isLive) 1f else Store.playSpeed())
                it.setSeekParameters(SeekParameters.CLOSEST_SYNC) // swipe and arrow seeks land on the nearest keyframe: much quicker
                // 电视上自动匹配帧率：片源写了帧率在 onVideoSizeChanged 里处理，没写的在这里逐帧估
                if (isTv && Store.matchFrameRate()) it.setVideoFrameMetadataListener { presentationTimeUs, _, format, _ ->
                    if (format.frameRate <= 0f) frameEstimator.add(presentationTimeUs)?.let { fps -> handler.post { matchFrameRate(fps) } }
                }
            }
        controlPlayer = EpisodePlayer(player!!, ::canStepEpisode, ::stepEpisode)
        preloadManager = preloadBuilder.build().also { manager ->
            // A source whose preload failed (a hiccup on the playlist, a timeout) must not be handed to the player later
            // as if it were good: it goes, and the episode is then started the normal way.
            manager.addListener(object : PreloadManagerListener {
                override fun onError(error: PreloadException) {
                    handler.post {
                        preloaded.entries.removeAll { it.value.item == error.mediaItem }
                        if (error.mediaItem != playingPreload) manager.remove(error.mediaItem)
                    }
                }
            })
        }

        setContentView(playLayout())
        bindViews()
        applySystemBars()

        onBackPressedDispatcher.addCallback(this) {
            if (locked) {
                // 锁着的时候返回也不退出（侧滑返回最容易误触），先露出锁键
                showLockBriefly()
                flashHud("屏幕已锁定，点左边的锁解锁", 1_500, R.drawable.ic_lock)
            } else if (isTv && nextCard?.visibility == View.VISIBLE) {
                cancelNext()
            } else if (vfullShown) {
                setPortraitFull(false)
            } else if (overlay) {
                // TV: back first hides the control bar, then returns to the details page (on demand) or leaves.
                if (panel?.visibility == View.VISIBLE) setPanelVisible(false)
                else if (!isLive) setTvFull(false)
                else finish()
            } else if (panel?.visibility == View.GONE) {
                panel?.visibility = View.VISIBLE
                playerView.setFullscreenButtonState(false)
            } else if (!isTv && requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                finish()
            }
        }

        if (isLive) {
            refreshLists()
            if (liveUrls.isEmpty()) setInfo("这个频道没有可用线路") else {
                applyRememberedLine()
                needWarm = true
                playCurrent(0)
            }
        } else {
            lifecycleScope.launch { initVod() }
        }
        handler.postDelayed(ticker, 1_000)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (landscape && !isInPictureInPictureMode) portraitFull = false // turned sideways: the landscape picture takes over
        if (locked) setLocked(false) // 新布局的锁键和控制栏从未锁状态开始
        abandonPull()
        detachViews()
        setContentView(playLayout())
        bindViews()
        refreshLists()
        updateFavorite()
        setInfo(infoText)
        applySystemBars()
        if (isInPictureInPictureMode) applyPip(true)
    }

    private fun bindViews() {
        playerView = findViewById(R.id.player)
        // The listener first: attaching the player may show the controller right away (paused, ended) and that must be seen.
        controllerShown = false
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener {
            controllerShown = it == View.VISIBLE
            syncThinProgress()
            syncLandOverlay()
            // 控制栏出来时续播提示会压在进度条上：先收起（点提示本身的「从头播放」不受影响，它在控制栏上面）
            if (controllerShown) resumeTip?.takeIf { t -> t.visibility == View.VISIBLE }?.let { t -> handler.removeCallbacks(hideResumeTip); fadeTo(t, false, gone = true) }
        })
        // 先于 setPlayer：挂上播放器时 PlayerView 立刻判断要不要显示，换布局后的新 PlayerView 也照样不自动弹
        playerView.controllerAutoShow = !quietControls
        playerView.player = controlPlayer ?: player
        // The controller's own gear opens the same panel as the app's: one place for speed, picture, subtitles and the rest.
        playerView.findViewById<View?>(androidx.media3.ui.R.id.exo_settings)?.setOnClickListener { showPlaySettings() }
        playerView.resizeMode = resizeModeOf(Store.resizeMode())
        findViewById<TextView?>(R.id.title)?.text = title
        findViewById<View?>(R.id.back)?.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        // The tall full screen's own buttons: that layer is part of this layout and fades in as the picture grows.
        findViewById<View?>(R.id.vBack)?.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        findViewById<View?>(R.id.vExit)?.setOnClickListener { setPortraitFull(false) }
        findViewById<View?>(R.id.vSettings)?.setOnClickListener { showPlaySettings() }
        findViewById<View?>(R.id.vCast)?.setOnClickListener { showCast() }
        findViewById<View?>(R.id.vEpisodes)?.setOnClickListener { showEpisodes() }
        panel = findViewById(R.id.panel)
        progressText = findViewById(R.id.progressText)
        findViewById<View?>(R.id.fullscreen)?.setOnClickListener { setTvFull(true) }
        if (isTv && !overlay) playerView.setOnClickListener { setTvFull(true) }
        playPauseBtn = findViewById<MaterialButton?>(R.id.playPause)?.also {
            it.setOnClickListener { player?.let { p -> if (p.isPlaying) p.pause() else p.play() } }
        }
        findViewById<View>(R.id.cast).apply {
            setOnClickListener { showCast() }
            // Casting is for sending a phone's video to the TV; on the TV itself there is nothing to cast to.
            if (isTv) visibility = View.GONE
        }
        findViewById<View>(R.id.playSettings).setOnClickListener { showPlaySettings() }
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
        findViewById<RecyclerView?>(R.id.epGroups)?.adapter = groupAdapter
        findViewById<View?>(R.id.desc)?.setOnClickListener { showSynopsis() }
        episodesView = findViewById<RecyclerView?>(R.id.episodes)?.also {
            // TV bar: one row that scrolls sideways, so the bar stays low; elsewhere a grid.
            it.layoutManager = if (isTv) LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            else GridLayoutManager(this, if (panel != null) 3 else 4)
            epAdapter.fill = !isTv
            it.adapter = epAdapter
            if (isLive) it.visibility = View.GONE
        }
        epLabel = findViewById<TextView?>(R.id.epLabel)?.also { if (isLive) it.visibility = View.GONE }

        playerView.setFullscreenButtonClickListener { full ->
            if (panel != null) {
                panel?.visibility = if (full) View.GONE else View.VISIBLE
            } else if (!isTv) {
                if (!isLive && (portraitVideo || portraitFull) && !landscape) {
                    setPortraitFull(full) // a tall picture fills the screen as it is; turning the phone sideways would only shrink it
                } else {
                    // Phones turn the screen; on TV the picture goes full screen inside the app (setTvFull) and the orientation stays.
                    requestedOrientation =
                        if (full) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                }
            }
        }
        playerView.setFullscreenButtonState(if (panel != null) panel?.visibility == View.GONE else landscape || vfullShown)
        findViewById<View?>(R.id.vOverlay)?.let { layer ->
            // Under the camera cut-out and the bars that swipe back in, the buttons must stay reachable.
            ViewCompat.setOnApplyWindowInsetsListener(layer) { v, insets ->
                val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                v.setPadding(i.left, i.top, i.right, i.bottom)
                insets
            }
            ViewCompat.requestApplyInsets(layer)
        }
        // A layout that was rebuilt while the tall full screen is on (turned back from sideways) goes straight to it.
        findViewById<CollapsingContent?>(R.id.content)?.let {
            it.onPreScroll = ::collapseBy
            it.list = findViewById(R.id.episodes)
            it.onPullStart = ::sheetPullStart
            it.onPull = ::sheetPull
            it.onPullEnd = ::sheetPullEnd
        }
        if (vfullShown) applyPortraitFull(animate = false) else {
            updateOverlayTitle()
            syncPullArrow()
            applyPlayerBox()
        }
        setupGestures()
        applySubtitleStyle()
        setupNextCard()
        setupResumeTip()
        setupLandControls()
        if (overlay) {
            updatePlayPause()
            setPanelVisible(true)
        } else if (isTv) {
            (findViewById<View?>(R.id.fullscreen) ?: playerView).requestFocus()
        }
    }

    /** Before a layout swap: let go of the old views so the player and the shared adapters do not keep them. */
    private fun detachViews() {
        if (::playerView.isInitialized) playerView.player = null
        linesView?.adapter = null
        episodesView?.adapter = null
        findViewById<RecyclerView?>(R.id.epGroups)?.adapter = null
    }

    /** TV, on demand: switch between the details page and full screen; playback carries on. */
    private fun setTvFull(full: Boolean) {
        if (tvFull == full) return
        tvFull = full
        handler.removeCallbacks(hidePanel)
        detachViews()
        setContentView(playLayout())
        bindViews()
        refreshLists()
        updateFavorite()
        setInfo(infoText)
        applySystemBars()
        if (full) {
            setPanelVisible(false)
            flashHud("$title  ${epName()}", 1_500)
        }
    }

    // ---- 往下拖小窗下面那一栏，进竖屏全屏（跟手，和 B 站一样） ---------------------------------------------------------

    /** 小窗里的画面要放大、移动多少才和全屏的画面一样；[range]：下面那一栏从现在的位置拖到屏幕底要走多远。 */
    private class Pull(val scale: Float, val dy: Float, val range: Float)

    /** 拖动中的样子：画面相对小窗的缩放和位移，页面其余部分还剩多少（1 原样，0 全屏）。 */
    private class Pose(val scale: Float, val ty: Float, val page: Float)

    private var pull: Pull? = null
    private var pullT = 0f
    private var pose = Pose(1f, 0f, 1f)
    private var pullAnimator: ValueAnimator? = null
    private var sheetPulled = 0f // 这一栏已经被拖下去多少像素

    /** 接管小窗准备拖进全屏；算不出来（还没有画面尺寸、还没排版）时返回 false。 */
    private fun beginPull(): Boolean {
        if (pull != null) return false // 上一次还在收尾
        val root = findViewById<ConstraintLayout?>(R.id.root) ?: return false
        val content = findViewById<View?>(R.id.content) ?: return false
        val size = player?.videoSize ?: return false
        if (size.width <= 0 || size.height <= 0 || playerView.height <= 0 || root.height <= 0) return false
        val aspect = size.width * size.pixelWidthHeightRatio / size.height
        val shownH = minOf(playerView.height.toFloat(), root.width / aspect) // 现在的画面：放进小窗
        val fullW = minOf(root.width.toFloat(), root.height * aspect) // 全屏时的画面：放进整个屏幕
        val scale = (fullW / aspect) / shownH
        val dy = root.height / 2f - (playerView.top + playerView.height / 2f)
        pull = Pull(scale, dy, (root.height - content.top).toFloat().coerceAtLeast(dp(120).toFloat()))
        pullT = 0f
        sheetPulled = 0f
        playerView.hideController()
        playerView.translationZ = dp(8).toFloat() // 盖在后画的列表上面
        root.setBackgroundColor(Color.BLACK)
        return true
    }

    private fun setPose(p: Pose) {
        pose = p
        playerView.scaleX = p.scale
        playerView.scaleY = p.scale
        playerView.translationY = p.ty
        findViewById<View?>(R.id.topbar)?.alpha = p.page
        // 下面那一栏跟着手指往下走（画面在它上面放大）
        findViewById<View?>(R.id.content)?.translationY = (1f - p.page) * (pull?.range ?: 0f)
    }

    /** 拖进全屏的进度 [t]：0 是小窗，1 看起来就是全屏。 */
    private fun setPull(t: Float) {
        val g = pull ?: return
        pullT = t
        setPose(Pose(1f + (g.scale - 1f) * t, g.dy * t, 1f - t))
    }

    /** 这一栏能不能往下拖进全屏：竖屏视频的小窗，没有别的拖动在进行（列表的情况由 CollapsingContent 先判断）。 */
    private fun sheetPullStart(): Boolean =
        phoneStack && !landscape && !vfullShown && pull == null && feed == null && portraitVideo && canPortraitFull

    /** 这一栏被往下拖了 [dy] 像素（往回拖是负数）：小窗没撑到最高就先撑高，撑满了再拖着这一栏进全屏。 */
    private fun sheetPull(dy: Float) {
        var rest = dy
        if (pull == null) {
            // 从这一栏拖：不管列表滚到哪，先把收矮的小窗撑高（从列表里拖、列表没在顶上时根本走不到这里）
            if (rest > 0 && boxCollapse > 0f) {
                rest += collapseBy(-rest.roundToInt(), null) // 用掉的是负数
                if (rest <= 0f) return
            }
            if (rest <= 0f || !beginPull()) return
        }
        val g = pull ?: return
        sheetPulled = (sheetPulled + rest).coerceIn(0f, g.range)
        setPull(sheetPulled / g.range)
    }

    private fun sheetPullEnd(up: Boolean, velocityY: Float) = endPull(up, velocityY)

    /** 松手：拖过三成，或者往下一甩，就进全屏；不够就弹回。 */
    private fun endPull(up: Boolean, velocityY: Float = 0f) {
        val g = pull ?: return
        val flick = velocityY > dp(FLICK_DP) && pullT > 0.05f
        val commit = up && (pullT >= 0.3f || flick)
        val from = pose
        val to = if (commit) Pose(g.scale, g.dy, 0f) else Pose(1f, 0f, 1f)
        pullAnimator?.cancel()
        var cancelled = false
        pullAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200L + (160 * abs(to.page - from.page)).toLong()
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                setPose(Pose(from.scale + (to.scale - from.scale) * f, from.ty + (to.ty - from.ty) * f, from.page + (to.page - from.page) * f))
            }
            doOnCancel { cancelled = true }
            doOnEnd { if (!cancelled) finishPull(commit) }
            start()
        }
    }

    /** 拖到一半页面要重建或离开：旧的视图上什么都不收尾，拖动直接作废。 */
    private fun abandonPull() {
        pullAnimator?.removeAllListeners()
        pullAnimator?.cancel()
        pullAnimator = null
        pull = null
        pose = Pose(1f, 0f, 1f)
        abandonFeed()
    }

    /** 拖动结束：画面回到原位；如果结果是全屏，页面真的换成全屏布局。 */
    private fun finishPull(full: Boolean) {
        setPose(Pose(1f, 0f, 1f))
        playerView.translationZ = 0f
        pull = null
        pullAnimator = null
        if (full && portraitVideo && canPortraitFull) {
            portraitFull = true
            applyPortraitFull(animate = false)
            applySystemBars()
            findViewById<View?>(R.id.vOverlay)?.apply {
                alpha = 0f
                animate().alpha(1f).setDuration(180).start()
            }
            playerView.showController()
            showFullTip()
        } else {
            findViewById<ConstraintLayout?>(R.id.root)?.setBackgroundColor(getColor(R.color.bg))
            applyPlayerBox()
        }
    }

    /** 头几次进竖屏全屏（不止一集时）：告诉怎么切集。 */
    private fun showFullTip() {
        val episodes = details[curSrc]?.lines?.getOrNull(curLine)?.episodes?.size ?: 0
        if (Store.swipeTips() >= 3 || episodes <= 1) return
        Store.bumpSwipeTips()
        flashHud("上滑下一集，下滑上一集", 2_500)
    }

    // ---- 竖屏全屏里上下滑切集（整页跟手，和抖音一样） ------------------------------------------------------------------

    /** 进行中的一次滑动：[dir] 1 往上滑去下一集、-1 往下滑去上一集；[range] 一整页多高；[exists] 那一集有没有。 */
    private class Feed(val dir: Int, val range: Float, val exists: Boolean)

    private var feed: Feed? = null
    private val velocity = VelocityTracker.obtain()
    private var feedOffset = 0f
    private var feedAnimator: ValueAnimator? = null
    private var tunnelRequested = false // 建播放器时有没有要求隧道播放（设置之后再改不影响这个播放器）

    /**
     * 竖屏全屏里中间上滑（下一集）或下滑（上一集）：这一集的画面跟着手指走，那一集的卡片（海报 + 集名）从下面（上面）
     * 跟进来。那一集的真画面要等切过去才有：同时开两个播放器太费解码器和流量，所以先用卡片顶着，第一帧出来再淡出。
     */
    private fun beginFeed(dir: Int): Boolean {
        if (feed != null || pull != null) return false
        val root = findViewById<View?>(R.id.root) ?: return false
        val card = findViewById<View?>(R.id.vNext) ?: return false
        if (root.height <= 0) return false
        val next = if (isLive || replayMode) null else details[curSrc]?.lines?.getOrNull(curLine)?.episodes?.getOrNull(curEp + dir)
        handler.removeCallbacks(hideNextCard)
        card.animate().cancel()
        if (next != null) {
            findViewById<TextView?>(R.id.vNextName)?.text = next.name
            findViewById<View?>(R.id.vNextSpin)?.visibility = View.INVISIBLE
            findViewById<ImageView?>(R.id.vNextPoster)?.let { img ->
                if (img.tag == null) {
                    img.tag = true
                    img.load(details[curSrc]?.pic?.ifEmpty { null } ?: pic.ifEmpty { null })
                    if (Build.VERSION.SDK_INT >= 31) img.setRenderEffect(RenderEffect.createBlurEffect(40f, 40f, Shader.TileMode.CLAMP))
                }
            }
            card.alpha = 1f
            card.translationZ = dp(10).toFloat()
            card.visibility = View.VISIBLE
        } else card.visibility = View.GONE
        feed = Feed(dir, root.height.toFloat(), next != null)
        playerView.hideController()
        setFeedOffset(0f)
        return true
    }

    /** 手指往上走了 [up] 像素（往下是负数）。那个方向没有集了，页面只动一点点，像被拉住一样。 */
    private fun feedDrag(up: Float) {
        val f = feed ?: return
        val d = up * f.dir
        setFeedOffset(if (f.exists) d.coerceIn(0f, f.range) else (d / 3f).coerceIn(0f, f.range / 5f))
    }

    private fun setFeedOffset(d: Float) {
        val f = feed ?: return
        feedOffset = d
        playerView.translationY = -d * f.dir
        findViewById<View?>(R.id.vOverlay)?.translationY = -d * f.dir
        if (f.exists) findViewById<View?>(R.id.vNext)?.translationY = (f.range - d) * f.dir
    }

    /** 松手：滑过五分之一屏，或者往那个方向一甩，就切过去；不够就弹回。 */
    private fun endFeed(up: Boolean, velocityY: Float) {
        val f = feed ?: return
        val flick = -velocityY * f.dir > dp(FLICK_DP) && feedOffset > dp(16)
        val commit = up && f.exists && (feedOffset > f.range * 0.2f || flick)
        Log.d("TVP", "feed swipe: ${feedOffset.roundToInt()}px, ${velocityY.roundToInt()}px/s -> ${if (!commit) "back" else if (f.dir > 0) "next" else "previous"}")
        if (up && !f.exists && feedOffset > dp(16)) {
            val count = details[curSrc]?.lines?.getOrNull(curLine)?.episodes?.size ?: 0
            flashHud(if (count <= 1) "这部只有一集" else if (f.dir > 0) "已经是最后一集" else "已经是第一集", 1_200)
        }
        val to = if (commit) f.range else 0f
        feedAnimator?.cancel()
        var cancelled = false
        feedAnimator = ValueAnimator.ofFloat(feedOffset, to).apply {
            duration = 160L + (160 * abs(to - feedOffset) / f.range).toLong()
            interpolator = DecelerateInterpolator()
            addUpdateListener { setFeedOffset(it.animatedValue as Float) }
            doOnCancel { cancelled = true }
            doOnEnd { if (!cancelled) finishFeed(if (commit) f.dir else 0) }
            start()
        }
    }

    /** 滑动结束。切到那一集（[step] 1 或 -1，0 不切）：卡片一直盖着，等它第一帧画面出来。 */
    private fun finishFeed(step: Int) {
        feed = null
        feedAnimator = null
        playerView.translationY = 0f
        findViewById<View?>(R.id.vOverlay)?.translationY = 0f
        val card = findViewById<View?>(R.id.vNext) ?: return
        val count = details[curSrc]?.lines?.getOrNull(curLine)?.episodes?.size ?: 0
        if (step == 0 || curEp + step !in 0 until count) {
            card.visibility = View.GONE
            return
        }
        card.translationY = 0f
        findViewById<View?>(R.id.vNextSpin)?.visibility = View.VISIBLE
        selectEpisode(curEp + step)
        handler.removeCallbacks(hideNextCard)
        handler.postDelayed(hideNextCard, NEXT_CARD_MAX_MS) // 到时候还没起播，就让播放器自己的转圈接手
    }

    /** 切过去的那一集的卡片淡出（第一帧出来了，或者等太久了）。 */
    private val hideNextCard = Runnable {
        if (feed != null) return@Runnable
        findViewById<View?>(R.id.vNext)?.takeIf { it.visibility == View.VISIBLE }?.animate()?.alpha(0f)?.setDuration(160)
            ?.withEndAction { findViewById<View?>(R.id.vNext)?.visibility = View.GONE }?.start()
    }

    private fun abandonFeed() {
        feedAnimator?.removeAllListeners()
        feedAnimator?.cancel()
        feedAnimator = null
        feed = null
        handler.removeCallbacks(hideNextCard)
    }

    // ---- 竖屏视频的小窗：比 16:9 高，列表往上滚时收矮（和 B 站一样） ---------------------------------------------------

    /** 0：竖屏视频的小窗撑到最高；1：收到 16:9。 */
    private var boxCollapse = 0f

    /** 竖屏视频小窗的高度范围（最高按画面比例、最多页面一半高，最矮 16:9）；不是竖屏视频的小窗时为 null。 */
    private fun boxRange(width: Int, innerH: Int): Pair<Int, Int>? {
        if (!portraitVideo || !canPortraitFull || width <= 0 || innerH <= 0) return null
        val size = player?.videoSize ?: return null
        if (size.width <= 0 || size.height <= 0) return null
        val aspect = size.width * size.pixelWidthHeightRatio / size.height
        val min = width * 9 / 16
        val tall = minOf(width / aspect, innerH * TALL_BOX).toInt()
        return if (tall > min) tall to min else null
    }

    private fun boxHeight(range: Pair<Int, Int>) = (range.first - (range.first - range.second) * boxCollapse).roundToInt()

    private fun innerHeight(root: View) = root.height - root.paddingTop - root.paddingBottom

    /** 小窗的高度跟着画面：竖屏视频撑高（按收缩到哪了），其余 16:9。全屏时和拖动画面时不动。 */
    private fun applyPlayerBox(animate: Boolean = false) {
        if (!phoneStack || landscape || vfullShown || pull != null || !::playerView.isInitialized) return
        val root = findViewById<ConstraintLayout?>(R.id.root) ?: return
        val lp = playerView.layoutParams as? ConstraintLayout.LayoutParams ?: return
        if (root.height == 0) {
            root.doOnLayout { applyPlayerBox() }
            return
        }
        val range = boxRange(root.width, innerHeight(root))
        val ratio = if (range == null) "16:9" else null
        val h = range?.let(::boxHeight) ?: 0
        if (lp.dimensionRatio == ratio && lp.height == h) return
        if (animate) TransitionManager.beginDelayedTransition(root, ChangeBounds().setDuration(220).setInterpolator(DecelerateInterpolator()))
        lp.dimensionRatio = ratio
        lp.height = h
        playerView.layoutParams = lp
    }

    /**
     * 竖着滚了 [dy]：往上滚先把高小窗收矮，往下拉把它撑高。[target] 是在滚的列表，它没在顶上时往下拉归列表自己；
     * 为 null 时（拖的是这一栏的其余地方）不管列表在哪都撑高。返回用掉的距离。
     */
    private fun collapseBy(dy: Int, target: View?): Int {
        if (!phoneStack || landscape || vfullShown || pull != null) return 0
        val root = findViewById<ConstraintLayout?>(R.id.root) ?: return 0
        val range = boxRange(root.width, innerHeight(root)) ?: return 0
        val cur = boxHeight(range)
        val next = when {
            dy > 0 -> maxOf(range.second, cur - dy)
            dy < 0 && target?.canScrollVertically(-1) != true -> minOf(range.first, cur - dy)
            else -> cur
        }
        if (next == cur) return 0
        boxCollapse = (range.first - next).toFloat() / (range.first - range.second)
        applyPlayerBox()
        return cur - next
    }

    /** The arrow under a tall picture: there to say it can be pulled down; gone elsewhere (and then it takes no room). */
    private fun syncPullArrow() {
        findViewById<View?>(R.id.pullArrow)?.visibility = if (portraitVideo && canPortraitFull && !vfullShown) View.VISIBLE else View.GONE
    }

    /** The root's padding for the status bar, kept while the full screen has taken it away. */
    private var pagePadding: IntArray? = null

    /**
     * Puts the page into (or back out of) the tall full screen in place: the same picture view grows from its 16:9 box to the
     * whole screen while the top bar and the lists fade out and the full-screen layer fades in (and the other way round on
     * the way back). It is not a new layout, so the picture is never torn down: no black flash, and the move can be animated.
     */
    private fun applyPortraitFull(animate: Boolean) {
        val root = findViewById<ConstraintLayout?>(R.id.root) ?: return
        val full = vfullShown
        val set = ConstraintSet().apply { clone(this@PlayActivity, R.layout.activity_play) }
        if (full) {
            set.setDimensionRatio(R.id.player, null)
            set.connect(R.id.player, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            set.connect(R.id.player, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            set.setVisibility(R.id.topbar, View.GONE)
            set.setVisibility(R.id.content, View.GONE)
            set.setVisibility(R.id.vOverlay, View.VISIBLE)
            if (pagePadding == null) pagePadding = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
        }
        if (animate) {
            TransitionManager.beginDelayedTransition(
                root,
                TransitionSet().addTransition(ChangeBounds()).addTransition(Fade()).setDuration(280).setInterpolator(DecelerateInterpolator()),
            )
        }
        set.applyTo(root)
        // The status bar's room goes with the bars in the same layout pass, so it moves with the picture, not after it.
        if (full) {
            root.fitsSystemWindows = false
            root.setPadding(0, 0, 0, 0)
        } else {
            pagePadding?.let { root.setPadding(it[0], it[1], it[2], it[3]) }
            pagePadding = null
            root.fitsSystemWindows = true
            root.requestApplyInsets()
        }
        root.setBackgroundColor(if (full) Color.BLACK else getColor(R.color.bg))
        if (!full) applyPlayerBox()
        playerView.setFullscreenButtonState(full)
        updateOverlayTitle()
        syncThinProgress()
        syncPullArrow()
        if (animate && full) playerView.showController() // so the way out (and the time bar) is in view on arrival
    }

    /** The full-screen title says which episode this is; the episode button is there only when there is more than one. */
    private fun updateOverlayTitle() {
        val episodes = details[curSrc]?.lines?.getOrNull(curLine)?.episodes?.size ?: 0
        findViewById<TextView?>(R.id.vTitle)?.text = if (episodes > 1) "$title · ${epName()}" else title
        findViewById<View?>(R.id.vEpisodes)?.visibility = if (episodes > 1) View.VISIBLE else View.GONE
        episodeBarBtn?.visibility = if (episodes > 1) View.VISIBLE else View.GONE
    }

    /** The hairline at the bottom of the tall full screen shows how far along the episode is while the controls are away. */
    private fun syncThinProgress() {
        val bar = findViewById<ProgressBar?>(R.id.vProgress) ?: return
        bar.visibility = if (controllerShown) View.INVISIBLE else View.VISIBLE
        val p = player ?: return
        if (p.duration > 0) bar.progress = (p.currentPosition * 1000 / p.duration).toInt().coerceIn(0, 1000)
    }

    // ---- 手机横屏全屏：顶栏、锁屏、倍速 / 选集（参照腾讯视频、B 站） --------------------------------------------------

    /** 横屏全屏的浮层只在手机横屏布局里有：顶栏的刘海边距、锁键、控制栏右下角的「倍速」「选集」 */
    private fun setupLandControls() {
        speedBarBtn = null
        episodeBarBtn = null
        val layer = findViewById<View?>(R.id.landOverlay) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(layer) { v, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(layer)
        findViewById<View?>(R.id.lock)?.setOnClickListener { setLocked(!locked) }
        // 底栏的齿轮和顶栏的设置是同一个面板：顶栏有设置时只留顶栏那个（平板的设置在右边面板里，齿轮留着给全屏用）
        if (layer.findViewById<View?>(R.id.playSettings) != null) {
            playerView.findViewById<View?>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
        }
        val bar = playerView.findViewById<ViewGroup?>(androidx.media3.ui.R.id.exo_basic_controls)
        if (bar != null && !isLive) {
            val height = playerView.findViewById<View?>(androidx.media3.ui.R.id.exo_fullscreen)?.layoutParams?.height ?: dp(48)
            fun barButton(text: String, onClick: () -> Unit) = TextView(this).apply {
                this.text = text
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 15f
                setPadding(dp(12), 0, dp(12), 0)
                background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null)
                setOnClickListener { onClick() }
            }
            speedBarBtn = barButton("倍速") { showSpeedPanel() }
            episodeBarBtn = barButton("选集") { showEpisodes() }
            bar.addView(speedBarBtn, 0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, height))
            bar.addView(episodeBarBtn, 1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, height))
            updateSpeedLabel()
            updateOverlayTitle()
        }
        syncLandOverlay(animate = false)
    }

    /** 底栏「倍速」显示现在的倍速，1× 时就写「倍速」 */
    private fun updateSpeedLabel() {
        val speed = Store.playSpeed()
        speedBarBtn?.text = if (abs(speed - 1f) < 0.01f) "倍速" else speedText(speed)
    }

    private fun speedText(speed: Float) = "${speed}×".replace(".0×", "×")

    /** 顶栏和锁键跟着控制栏一起淡入淡出；锁着的时候顶栏不出来，锁键由 [showLockBriefly] 管 */
    private fun syncLandOverlay(animate: Boolean = true) {
        val top = findViewById<View?>(R.id.landTop) ?: return
        val show = controllerShown && !locked
        if (show) updateClock()
        fadeTo(top, show, animate = animate)
        val lock = findViewById<View?>(R.id.lock) ?: return
        if (!locked) fadeTo(lock, show, animate = animate)
    }

    /** 顶栏右边的时间和电量（全屏时系统状态栏是藏起来的） */
    private fun updateClock() {
        val clock = findViewById<TextView?>(R.id.landClock) ?: return
        val now = java.util.Calendar.getInstance()
        val time = "%02d:%02d".format(now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE))
        val battery = (getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager)
            ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        clock.text = if (battery != null) "$time   电量 $battery%" else time
    }

    /** 透明度渐变显示 / 隐藏；藏起来以后设成不可见，不挡下面的点击 */
    private fun fadeTo(v: View, show: Boolean, animate: Boolean = true, gone: Boolean = false) {
        v.animate().cancel()
        val hidden = if (gone) View.GONE else View.INVISIBLE
        if (!animate || animationsOff()) {
            v.alpha = 1f
            v.visibility = if (show) View.VISIBLE else hidden
            return
        }
        if (show) {
            if (v.visibility != View.VISIBLE) {
                v.alpha = 0f
                v.visibility = View.VISIBLE
            }
            v.animate().alpha(1f).setDuration(160).start()
        } else if (v.visibility == View.VISIBLE) {
            v.animate().alpha(0f).setDuration(160).withEndAction { v.visibility = hidden; v.alpha = 1f }.start()
        }
    }

    private fun animationsOff() = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    /** 锁屏：控制栏收起、手势停用、屏幕方向固定；解锁恢复原样 */
    private fun setLocked(on: Boolean) {
        if (locked == on) return
        locked = on
        handler.removeCallbacks(hideLock)
        findViewById<ImageButton?>(R.id.lock)?.apply {
            setImageResource(if (on) R.drawable.ic_lock else R.drawable.ic_lock_open)
            contentDescription = if (on) "解锁屏幕" else "锁定屏幕"
        }
        if (on) {
            orientationBeforeLock = requestedOrientation
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            playerView.hideController()
            playerView.useController = false
            // 挂在画面上的提示自己能接点击，锁屏拦不住：先收起（下一集照样按时自动播放）
            handler.removeCallbacks(hideResumeTip)
            resumeTip?.visibility = View.GONE
            nextCard?.visibility = View.GONE
            flashHud("屏幕已锁定", 1_000, R.drawable.ic_lock)
            showLockBriefly()
        } else {
            requestedOrientation = orientationBeforeLock
            if (::playerView.isInitialized && !isInPictureInPictureMode) {
                playerView.useController = true
                playerView.showController()
            }
        }
        syncLandOverlay()
    }

    /** 锁着的时候点画面：锁键露出几秒再淡出 */
    private fun showLockBriefly() {
        val lock = findViewById<View?>(R.id.lock) ?: return
        fadeTo(lock, true)
        handler.removeCallbacks(hideLock)
        handler.postDelayed(hideLock, LOCK_SHOW_MS)
    }

    /** 倍速：右侧一列大字（腾讯视频横屏的样子），当前的用强调色 */
    private fun showSpeedPanel() {
        val speeds = listOf(3f, 2f, 1.5f, 1.25f, 1f, 0.75f)
        val now = Store.playSpeed()
        var dialog: android.app.Dialog? = null
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            for (speed in speeds) addView(TextView(this@PlayActivity).apply {
                text = speedText(speed)
                gravity = Gravity.CENTER
                textSize = 17f
                val current = abs(speed - now) < 0.01f
                setTextColor(if (current) getColor(R.color.accent) else Color.WHITE)
                if (current) setTypeface(typeface, android.graphics.Typeface.BOLD)
                isFocusable = true
                setBackgroundResource(R.drawable.bg_focus_row)
                setOnClickListener {
                    player?.setPlaybackSpeed(speed)
                    Store.setPlaySpeed(speed)
                    updateSpeedLabel()
                    dialog?.dismiss()
                    flashHud("${speedText(speed)} 倍速", 900)
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        }
        dialog = showSidePanel("倍速", android.widget.ScrollView(this).apply { isFillViewport = true; addView(list) }, width = 240)
    }

    /** Whether the previous (-1) or next (1) episode exists, for the controller's own buttons. */
    private fun canStepEpisode(step: Int): Boolean {
        if (isLive || replayMode) return false
        val count = details[curSrc]?.lines?.getOrNull(curLine)?.episodes?.size ?: return false
        return curEp + step in 0 until count
    }

    /** Phones: switch between the small picture and the tall picture over the whole screen; playback carries on. */
    private fun setPortraitFull(full: Boolean) {
        if (portraitFull == full) return
        if (full && !(portraitVideo && canPortraitFull)) return
        portraitFull = full
        applyPortraitFull(animate = true)
        applySystemBars()
        if (full) showFullTip()
    }

    /** The details page text: score, year / category / area / status, people, synopsis; the blurred backdrop. */
    private fun fillDetail() {
        val d = details[curSrc] ?: return
        findViewById<TextView?>(R.id.meta)?.text =
            listOf(d.score.takeIf { it.isNotEmpty() }?.let { "$it 分" }.orEmpty(), d.year, d.type, d.area, d.remarks)
                .filter { it.isNotEmpty() }.joinToString("  /  ")
        findViewById<ImageView?>(R.id.backdrop)?.let { img ->
            if (img.tag == null) {
                img.tag = true
                img.load(d.pic.ifEmpty { pic }.ifEmpty { null })
                if (Build.VERSION.SDK_INT >= 31) img.setRenderEffect(RenderEffect.createBlurEffect(60f, 60f, Shader.TileMode.CLAMP))
            }
        }
        findViewById<TextView?>(R.id.people)?.text = listOfNotNull(
            d.director.takeIf { it.isNotEmpty() }?.let { "导演：$it" },
            d.actor.takeIf { it.isNotEmpty() }?.let { "主演：$it" },
        ).joinToString("\n")
        findViewById<TextView?>(R.id.desc)?.text = d.content.ifEmpty { "暂无简介" }
    }

    /** TV control bar: showing it moves focus into it; it hides again after a few idle seconds while playing. */
    private fun setPanelVisible(show: Boolean) {
        val bar = panel ?: return
        handler.removeCallbacks(hidePanel)
        bar.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            updateProgress()
            listOfNotNull(playPauseBtn, favoriteBtn, findViewById(R.id.playSettings))
                .firstOrNull { it.visibility == View.VISIBLE }?.requestFocus()
            bumpPanel()
        } else {
            playerView.requestFocus()
        }
    }

    private fun bumpPanel() {
        handler.removeCallbacks(hidePanel)
        if (player?.isPlaying == true) handler.postDelayed(hidePanel, 6_000)
    }

    private fun updatePlayPause() {
        val b = playPauseBtn ?: return
        val playing = player?.isPlaying != false
        b.text = if (playing) "暂停" else "播放"
        b.visibility = if (isLive) View.GONE else View.VISIBLE
    }

    private fun updateProgress() {
        val p = player ?: return
        val known = canSeek() && p.duration > 0
        progressText?.text = if (!known) "" else "${formatTime(p.currentPosition)} / ${formatTime(p.duration)}"
        findViewById<ProgressBar?>(R.id.tvProgress)?.apply {
            visibility = if (known) View.VISIBLE else View.GONE
            if (known) {
                progress = (p.currentPosition * 1000 / p.duration).toInt().coerceIn(0, 1000)
                secondaryProgress = (p.bufferedPosition * 1000 / p.duration).toInt().coerceIn(0, 1000)
            }
        }
    }

    private fun applySystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        // The full-screen layouts (phone landscape, TV) hide the system bars; the picture + side panel layout keeps them.
        if ((landscape && panel == null) || isTv || vfullShown) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---- playback ------------------------------------------------------------------------------------------------

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            prefetchRoot = mediaItem?.localConfiguration?.uri?.toString()
            prefetch?.reset() // what was fetched ahead for the old stream goes now, not when the new one first asks
            frameEstimator.reset()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) noteDisturbance()
        }

        override fun onPlaybackStateChanged(state: Int) {
            updateKeepScreen()
            // Buffering in the middle of playing, not after a seek or at the start, is a stall.
            if (state == Player.STATE_BUFFERING && started && !isLive && player?.playWhenReady == true && SystemClock.elapsedRealtime() - lastSeekAt > 1_500) {
                stallPolicy.onStall()
                stalledThisEpisode = true
                Log.d("TVP", "stall ${stallPolicy.stalls}: ${stallPolicy.resumeMs()} ms are buffered before playing on")
                noteStall()
            }
            when (state) {
                Player.STATE_READY -> {
                    if (quietControls) setQuietControls(false)
                    endStall("缓过来了")
                    if (!started) Log.d("TVP", "ready on ${current?.label}")
                    // A few seconds into the episode, once it plays steadily, the next one is loaded ahead.
                    if (!isLive && !replayMode) {
                        handler.removeCallbacks(preloadNext)
                        handler.postDelayed(preloadNext, PRELOAD_AFTER_MS)
                    }
                    handler.removeCallbacks(rescue)
                    // Speed tests still running would only take bandwidth from the line that just started.
                    probeJobs.forEach { it.cancel() }
                    probeJobs = emptyList()
                    // A replayed programme's address is not a line of the channel: nothing to remember for it.
                    if (!replayMode) {
                        rememberLine()
                        (resolvedUrl ?: current?.url)?.let { Store.clearBadHost(it) }
                    }
                    handler.removeCallbacks(backgroundProbe)
                    handler.postDelayed(backgroundProbe, BACKGROUND_PROBE_MS)
                    handler.removeCallbacks(stall)
                    val resumed = pendingStart > 0
                    val wanted = pendingStart
                    started = true
                    pendingStart = 0
                    // Carried over from a line that cuts the series differently (one file for every episode against one
                    // per episode): a position past this stream's end would only finish it, so start it from the top.
                    val restart = !isLive && wanted > 0 && player?.let { p -> p.duration > 0 && wanted >= p.duration - 1_000 } == true
                    if (restart) player?.seekTo(0)
                    if (isLive) refreshEpg() else skipIntro(resumed && !restart)
                    if (resumeTipFrom > 0) {
                        if (resumed && !restart) showResumeTip(wanted)
                        resumeTipFrom = 0
                    }
                    setInfo(playingText())
                }
                Player.STATE_ENDED -> if (replayMode) setInfo("回看已播完，可以换一段，或点「回到直播」") else if (!isLive) {
                    if (!stalledThisEpisode) stallPolicy.onSmoothEpisode() // played through without a stall: ease off
                    if (sleepAfterEp) {
                        sleepNow("这一集播完了，已按睡眠定时停止")
                        return
                    }
                    val count = details[curSrc]?.lines?.getOrNull(curLine)?.episodes?.size ?: 0
                    if (nextCancelled) setInfo("这一集播完了，可以在选集里挑下一集")
                    else if (curEp + 1 < count) {
                        // 播完那一下 PlayerView 会把控制栏弹出来（不管它先收到还是我们先收到）：自动连播时收回去
                        setQuietControls(true)
                        playerView.hideController()
                        selectEpisode(curEp + 1)
                    }
                }
            }
        }

        override fun onRenderedFirstFrame() {
            handler.removeCallbacks(hideNextCard)
            handler.post(hideNextCard)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.height <= 0) return
            portraitVideo = videoSize.width * videoSize.pixelWidthHeightRatio < videoSize.height
            syncPullArrow()
            applyPlayerBox(animate = true)
            if (!portraitVideo && portraitFull) setPortraitFull(false)
            if (portraitVideo && canPortraitFull && !portraitFull && !vfullTipShown && Store.vfullTips() < 3) {
                vfullTipShown = true
                Store.bumpVfullTips()
                flashHud("往下拖下面的选集，进入竖屏全屏", 2_500)
            }
            player?.videoFormat?.frameRate?.takeIf { it > 0f }?.let(::matchFrameRate)
            resolution = qualityLabel(videoSize.height)
            if (!replayMode) lineQuality[lineKey()] = resolution // a replayed programme's picture is not a line's quality
            if (player?.playbackState == Player.STATE_READY) setInfo(playingText())
            refreshLists()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val now = SystemClock.elapsedRealtime()
            if (isPlaying) playingSince = now
            else if (playingSince > 0) {
                playedMs += now - playingSince
                playingSince = 0
            }
            updateKeepScreen()
            updatePlayPause()
            // Paused on TV: keep the bar up so the picture is not left frozen with nothing on screen.
            if (overlay && panel?.visibility == View.VISIBLE) bumpPanel()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updateKeepScreen()

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            if (!isLive && !speedBoost) {
                Store.setPlaySpeed(playbackParameters.speed)
                updateSpeedLabel()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.d("TVP", "player error ${error.errorCodeName} on ${current?.label}")
            if (retryInPlace(error)) return
            // The server answered with an error or garbage (not a timeout, not a dropped network): its other lines
            // are likely dead too for a while. Live lines are not judged by host.
            val sourceError = error.errorCode in setOf(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            )
            val online = (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetwork != null
            if (!isLive && !started && sourceError && online) (resolvedUrl ?: current?.url)?.let { Store.markBadHost(it) }
            setInfo("这条线路播放失败，正在换下一条…")
            failOver()
        }
    }

    /** 用了哪个解码器、掉了多少帧：卡顿时分得清是网络慢还是电视解不动。 */
    private val analytics = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            videoDecoder = decoderName
        }

        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            audioDecoder = decoderName
        }

        override fun onVideoDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) = addFrames(decoderCounters)

        // 播放器每丢 50 帧（或停下时）报一次。不缓冲也会一顿一顿的「卡」就是这种，单独记进日志
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            droppedSinceLog += droppedFrames
            val now = SystemClock.elapsedRealtime()
            if (droppedSinceLog < 50 || now - dropLoggedAt < DROP_LOG_MS) return
            dropLoggedAt = now
            PlayLog.add("掉帧 $droppedSinceLog 帧", "${logWhat()}\n最近一次 ${PlayNetStats.ms(elapsedMs)} 内丢了 $droppedFrames 帧")
            droppedSinceLog = 0
        }
    }

    private fun addFrames(counters: DecoderCounters) {
        counters.ensureUpdated()
        droppedFrames += counters.droppedBufferCount
        renderedFrames += counters.renderedOutputBufferCount
    }

    /**
     * 能在原线路补救的错误先补救一次，不急着换线：换线要重新解析、重新缓冲，用户看到的就是「又在加载」。
     * - 直播落后于直播窗口（卡太久，服务器上的分片已经过期）：跳回直播点接着放；
     * - 已经放起来的线路偶尔断网、超时、服务器报错或解码出错：原位置再准备一次。
     * 重试后一分钟内又出错才换线；重试后 20 秒还没缓过来，卡顿计时照常换线。
     */
    private fun retryInPlace(error: PlaybackException): Boolean {
        val p = player ?: return false
        if (!started || replayMode || playJob?.isActive == true) return false
        val code = error.errorCode
        val behindLive = code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW
        // 服务器明确回了错误（403 / 404 / 返回的不是视频）：原地再试多半还是这样，直接换线
        val transient = code in 2000..2999 && code !in setOf(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        ) || code == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            code == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
            code == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
        val now = SystemClock.elapsedRealtime()
        if (!behindLive && !transient || retriedAt > 0 && now - retriedAt < RETRY_WINDOW_MS) return false
        retriedAt = now
        retries++
        PlayLog.add("播放出错，在原线路重试", "${logWhat()}\n${error.errorCodeName}")
        setInfo(if (behindLive) "回到直播最新画面…" else "网络波动，正在重新连接这条线路…")
        if (behindLive) {
            p.seekToDefaultPosition()
            p.prepare()
        } else {
            // 断网、超时多半要过一会儿才恢复：等 2 秒再连，免得立刻又失败、白白用掉这次重试
            handler.removeCallbacks(retryNow)
            handler.postDelayed(retryNow, RETRY_DELAY_MS)
        }
        handler.removeCallbacks(stall)
        handler.postDelayed(stall, STALL_MS)
        return true
    }

    private val retryNow = Runnable {
        player?.takeIf { it.playbackState == Player.STATE_IDLE }?.prepare()
    }

    /**
     * No screen lock / screen saver while the video plays, buffers or changes line; paused, finished or failed, the
     * normal timeout applies again.
     */
    private fun updateKeepScreen() {
        val p = player
        val on = p != null && p.playWhenReady && p.playbackState != Player.STATE_ENDED && !allFailed
        if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun playingText(): String {
        val base = if (isLive) "正在播放：$title · ${current?.label.orEmpty()}" else "正在播放：${epName()} · ${current?.label.orEmpty()}"
        val line = if (resolution.isEmpty()) base else "$base · $resolution"
        return if (isLive && !replayMode) epgText()?.let { "$line\n$it" } ?: line else line
    }

    private fun clock(minutes: Int) = "%02d:%02d".format(minutes / 60 % 24, minutes % 60)

    private fun epgText(): String? {
        val now = Epg.cached(title) ?: return null
        val cur = now.current ?: return null
        return "现在：${clock(cur.start)}《${cur.title}》" + (now.next?.let { "\n接下来：《${it.title}》" } ?: "")
    }

    private fun refreshEpg() {
        val name = title
        lifecycleScope.launch {
            if (Epg.now(name) != null && started && name == title) setInfo(playingText())
        }
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

    private fun playCurrent(startMs: Long, first: Cand? = null, firstStream: String? = null) {
        replayMode = false
        quickFirst = first?.let { it to firstStream }
        gen++
        handler.removeCallbacks(backgroundProbe)
        introDone = false
        outroDone = false
        nextCancelled = false
        nextCard?.visibility = View.GONE
        seekPending = -1
        handler.removeCallbacks(commitSeek)
        allFailed = false
        updateKeepScreen()
        tried.clear()
        pendingStart = startMs
        started = false
        attempt()
    }

    /** 什么在放：片名、第几集、哪条线路、画质和码率。 */
    private fun logWhat(): String {
        val ep = if (isLive) "" else " 第${curEp + 1}集"
        val format = player?.videoFormat
        val rate = format?.bitrate?.takeIf { it > 0 }?.let { String.format(Locale.US, " %.1fMbps", it / 1e6) }.orEmpty()
        val size = format?.takeIf { it.height > 0 }?.let { " ${it.width}x${it.height}" }.orEmpty()
        val codec = format?.sampleMimeType?.removePrefix("video/")?.let { " $it" }.orEmpty()
        return "$title$ep · ${current?.label ?: "?"} ·$size$codec$rate".trimEnd(' ', '·') + decoderText()
    }

    /** 视频解码器（软解单独标出来）和音频解码器；开了隧道播放也写上。 */
    private fun decoderText(): String {
        if (videoDecoder.isEmpty()) return ""
        val soft = videoDecoder.startsWith("c2.android.") || videoDecoder.startsWith("OMX.google.")
        // 设置开了不等于用上了：设备或音轨不支持时播放器退回普通方式
        val tunnel = if (!tunnelRequested) "" else if (player?.isTunnelingEnabled == true) " · 隧道播放" else " · 隧道播放没生效（设备不支持）"
        return "\n解码：$videoDecoder${if (soft) "（软解）" else ""}${if (audioDecoder.isEmpty()) "" else " · 音频 $audioDecoder"}$tunnel"
    }

    /** 渲染了多少帧、丢了多少（还在放的这一段也算上）。 */
    private fun framesText(): String {
        var dropped = droppedFrames
        var rendered = renderedFrames
        player?.videoDecoderCounters?.let { c ->
            c.ensureUpdated()
            dropped += c.droppedBufferCount
            rendered += c.renderedOutputBufferCount
        }
        if (rendered + dropped == 0L) return "画面：没有统计到帧" + if (tunnelRequested) "（隧道播放时可能不统计）" else ""
        val pct = String.format(Locale.US, "%.1f", dropped * 100.0 / (rendered + dropped))
        return "画面：显示 $rendered 帧 · 掉帧 $dropped 帧（$pct%）"
    }

    private fun prefetchState() = if (prefetch != null && prefetchOn()) "开" else "关"

    /** 播放中途开始缓冲：记下当时的情况，缓过来（或换线、退出）时连同等了多久一起写进播放日志。 */
    private fun noteStall() {
        stallAt = SystemClock.elapsedRealtime()
        stallCount++
        stallNote = "${logWhat()}\n本次第 $stallCount 次卡顿 · 多连接预取${prefetchState()} · 恢复前要缓冲 ${stallPolicy.resumeMs() / 1000.0}s\n卡之前最近的请求：\n${netStats.recentText()}"
    }

    private fun endStall(how: String) {
        val note = stallNote ?: return
        val waited = SystemClock.elapsedRealtime() - stallAt
        stallTotalMs += waited
        stallNote = null
        PlayLog.add("卡顿 ${PlayNetStats.ms(waited)}，$how", note)
    }

    /** 离开播放页：这次播了多久、卡了几次、请求耗时汇总。没发出请求（打开就退出）不记。 */
    private fun logSummary() {
        if (netStats.requests == 0) return
        if (playingSince > 0) playedMs += SystemClock.elapsedRealtime() - playingSince
        playingSince = 0
        val minutes = String.format(Locale.US, "%.1f", playedMs / 60_000.0)
        val metered = (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
        PlayLog.add(
            "播放小结",
            "${logWhat()}\n播放 $minutes 分钟 · 卡顿 $stallCount 次共 ${PlayNetStats.ms(stallTotalMs)} · 换线 $failOvers 次 · " +
                "原线路重试 $retries 次 · ${if (metered) "计费网络" else "不计费网络"} · 多连接预取${prefetchState()}\n" +
                framesText() + (if (adCleaned.isNotEmpty()) "\n去广告：$adRule · ${adCleaned.size} 个播放列表（含预加载的下一集）共剔除 ${adCleaned.values.sum()} 个分片" else "") + "\n${netStats.summary()}",
        )
    }

    /**
     * A single failure can be reported twice (the stall timer, then the player's own timeout error). While the next
     * line is already being picked, the second report must be ignored: restarting would drop the line just picked.
     */
    private fun failOver() {
        // A replayed programme has no other line to fall back to: say so and let the viewer choose.
        if (replayMode) {
            setInfo("这段回看播放失败，可以换一段，或点「回到直播」")
            return
        }
        if (playJob?.isActive == true) return
        // A remembered live line that no longer starts should not keep costing a failed attempt every time.
        if (isLive && !started && current?.url != null && current?.url == Store.liveLine(title)) {
            Store.clearLiveLine(title)
            pinned = false
        }
        // Dropped mid-playback: carry on from here on the next line instead of starting over.
        if (!isLive && started) pendingStart = player?.currentPosition ?: pendingStart
        failOvers++
        attempt()
    }

    /** Picks the next untried line/source and starts it. */
    private fun attempt() {
        endStall("没缓过来，换了线路或重新开始")
        playJob?.cancel()
        // The episode that is starting needs the bandwidth more than the ones after it: what is still being resolved is
        // dropped. What the manager has loaded is kept (an invalidate here would empty the very source about to be played).
        preloadJob?.cancel()
        handler.removeCallbacks(preloadNext)
        stalledThisEpisode = false
        retriedAt = 0L // 新线路有自己的一次原地重试
        handler.removeCallbacks(retryNow)
        handler.removeCallbacks(stall)
        // 控制栏这时没出来就别让它自己弹出来；用户正开着的（比如刚点了下一集）照常按超时收起
        if (!controllerShown) setQuietControls(true)
        player?.stop()
        prefetch?.reset() // the line being left is no longer fetched ahead for, however long the next one takes to resolve
        started = false
        resolution = ""
        playJob = lifecycleScope.launch {
            if (needWarm) {
                needWarm = false
                handler.removeCallbacks(rescue)
                handler.postDelayed(rescue, RESCUE_MS)
            }
            while (true) {
                val quick = quickFirst
                quickFirst = null
                val cand = quick?.first?.also { tried += it.url } ?: nextCandidate()
                if (cand == null) {
                    // 播放器早已是 IDLE，只恢复开关不会再触发显示：主动把控制栏拿出来，不然只剩一块黑屏
                    setQuietControls(false)
                    playerView.showController()
                    player?.stop()
                    prefetch?.reset()
                    allFailed = true
                    if (portraitFull) setPortraitFull(false) // the message is on the page behind
                    updateKeepScreen()
                    setInfo(if (QuarkWeb.expired && items.any { it.sourceKey == QuarkWeb.SOURCE_KEY }) "夸克网盘的网页版登录已失效，请到设置里重新扫码登录" else "所有线路都试过了，没有能播放的。点这里重试，或换一部")
                    return@launch
                }
                setInfo("正在连接：${cand.label}")
                val stream = quick?.second ?: probes[cand.url]?.takeIf { it.ok }?.stream ?: if (isLive) cand.url else Resolve.stream(cand.url)
                Log.d("TVP", "candidate ${cand.label} ${cand.url} -> $stream, start at ${pendingStart}ms")
                if (stream == null) continue
                current = cand
                resolvedUrl = stream
                val media = mediaFor(stream)
                // The same address was loaded ahead (the manager already has its playlist and first seconds): play that
                // source. It is used once: after a failure the retry builds a fresh one.
                val ahead = if (isLive) null else preloaded[curEp]?.takeIf { it.stream == stream && it.subtitle == extSubtitle }
                val source = ahead?.let { preloadManager?.getMediaSource(it.item) }
                if (!isLive) Log.d("TVP", "episode ${curEp + 1}: preload ${if (source != null) "hit" else "miss"}")
                if (ahead != null) {
                    preloaded.remove(curEp)
                    if (source != null) retired += ahead.item
                }
                playingPreload = if (source != null) ahead?.item else null
                player?.apply {
                    // TIME_UNSET lets live streams start at the live edge instead of the oldest segment.
                    val at = if (pendingStart > 0) pendingStart else C.TIME_UNSET
                    if (source != null) setMediaSource(source, at) else setMediaItem(media, at)
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
            detailOf(next)
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

    /** Fetching ahead costs extra connections and data: wherever the line tests are skipped, it is too. */
    private fun prefetchOn(): Boolean = smartOn()

    /**
     * Smooth lines first, the sharpest of them leading; if none is smooth, the fastest; lines that failed the test
     * last. Lines with no test result keep their listed order after the tested ones. A hand-picked line stays first.
     */
    private fun rank(input: List<Cand>): List<Cand> {
        // Hosts that failed to start recently go last whatever the mode, so a dead source does not cost seconds on
        // every title (stable sort: the order is otherwise kept).
        val list = if (pinned) input.take(1) + input.drop(1).sortedBy { Store.isBadHost(it.url) }
        else input.sortedBy { Store.isBadHost(it.url) }
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
    private suspend fun warmUp(background: Boolean = false) {
        if (!smartOn() || pinned) return
        if (!isLive) {
            // A wider choice: bring in the lines of up to two more sources, in parallel.
            val extra = items.indices.filter { !details.containsKey(it) }.take(2)
            if (extra.isNotEmpty()) {
                coroutineScope {
                    extra.map { i -> async { withTimeoutOrNull(3_000) { detailOf(i) } } }.awaitAll()
                }
            }
        }
        // A slow-start rescue that is no longer needed: the line started while the details were loading.
        if (!background && started) return
        val cap = minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        val fresh = rawCandidates().filter { it.url !in probes }.take(6)
        if (fresh.size < 2) return
        probeJobs.forEach { it.cancel() }
        val jobs = fresh.map { c ->
            lifecycleScope.launch {
                val result = LineProbe.probe(c.url, cap)
                probes[c.url] = result
                Log.d("TVP", "probe ${c.label}: $result")
            }
        }
        probeJobs = jobs
        val t0 = System.currentTimeMillis()
        while (jobs.any { it.isActive }) {
            val waited = System.currentTimeMillis() - t0
            if (waited >= 4_000) break
            if (waited >= 1_500 && fresh.any { probes[it.url]?.smooth == true }) break
            delay(100)
        }
    }

    /**
     * Continue watching: the stream that played this episode last time (within [QUICK_MS]) starts at once, before
     * any source has answered; the details, line list and episode names fill in as they arrive. If that stream has
     * gone stale, the normal failover takes over.
     */
    private fun quickResume(saved: Library.Entry): Boolean {
        val q = Store.vodLast(key) ?: return false
        if (q.ep != saved.ep || System.currentTimeMillis() - q.time > QUICK_MS) return false
        val src = items.indexOfFirst { it.sourceKey == q.src }.takeIf { it >= 0 } ?: return false
        curSrc = src
        curLine = q.line
        curEp = q.ep
        needWarm = true // a stale stream still gets the slow-start rescue
        if (saved.posMs >= RESUME_TIP_MIN_MS) resumeTipFrom = saved.posMs
        playCurrent(saved.posMs, Cand(q.url, "${items[src].sourceName} · ${q.lineName}", src, q.line), q.stream)
        lifecycleScope.launch {
            val d = detailOf(src)
            if (d != null) {
                // The line list may have changed order since; follow the line by name.
                d.lines.indexOfFirst { it.name == q.lineName }.takeIf { it >= 0 }?.let { curLine = it }
                Library.markSeen(key, Tracker.countOf(d))
            }
            refreshLists()
            updateFavorite()
            if (started) setInfo(playingText())
            prefetchDetails()
        }
        return true
    }

    /**
     * Loads every source's details in the background, six at a time; each one that arrives adds its lines to the list
     * (and makes the failover choice wider).
     */
    private fun prefetchDetails() {
        val todo = items.indices.filter { !details.containsKey(it) }
        if (todo.isEmpty()) return
        lifecycleScope.launch {
            val gate = Semaphore(6)
            todo.map { i -> async { gate.withPermit { detailOf(i) }; refreshLists() } }.awaitAll()
        }
    }

    /**
     * The line to start with: one whose addresses are the video itself (…m3u8 / …mp4) before one that is a share page,
     * which first has to be fetched and searched for the real address (often several seconds).
     */
    private fun directLine(d: VodDetail): Int =
        d.lines.indexOfFirst { l -> l.episodes.firstOrNull()?.url?.let { DIRECT.containsMatchIn(it) } == true }.coerceAtLeast(0)

    /** Same episode number in every loaded source: the chosen source first, starting at the chosen line. */
    private fun rawVodCandidates(ep: Int): List<Cand> = buildList {
        val order = listOf(curSrc) + details.keys.filter { it != curSrc }.sorted()
        for (s in order) {
            val lines = details[s]?.lines ?: continue
            val start = if (s == curSrc) curLine else directLine(details[s]!!)
            for (k in lines.indices) {
                val li = (start + k) % lines.size
                lines[li].episodes.getOrNull(ep)?.let { add(Cand(it.url, "${items[s].sourceName} · ${lines[li].name}", s, li)) }
            }
        }
    }

    private val detailJobs = HashMap<Int, Deferred<VodDetail?>>()

    /**
     * A source's detail, loaded once however many callers ask at the same time (start, failover, prefetch, speed
     * test); a detail that loaded is never replaced by a later failure. A failed source is recorded as null.
     */
    private suspend fun detailOf(index: Int): VodDetail? {
        details[index]?.let { return it }
        val d = detailJobs.getOrPut(index) { lifecycleScope.async { loadDetail(index) } }.await()
        if (details[index] == null) details[index] = d
        return details[index]
    }

    private suspend fun loadDetail(index: Int): VodDetail? {
        val item = items[index]
        // A drive folder: its video files are the episodes.
        if (item.sourceKey == Quark.SOURCE_KEY) return runCatching { withTimeoutOrNull(15_000) { Quark.detail(item.id, item.title) } }.getOrNull()
        if (item.sourceKey == QuarkWeb.SOURCE_KEY) return runCatching { withTimeoutOrNull(15_000) { QuarkWeb.detail(item.id, item.title) } }.getOrNull()
        if (item.sourceKey == NetStore.SOURCE_KEY) return runCatching { withTimeoutOrNull(20_000) { NetStore.detail(item.id, item.title) } }.getOrNull()
        val source = sources[item.sourceKey] ?: return null
        return try {
            withTimeoutOrNull(12_000) { Cms.detail(source, item.id) }.also { Log.d("TVP", "detail ${item.sourceName}: ${if (it == null) "none" else "ok"}") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun initVod() {
        setInfo("正在加载线路…")
        val saved = Library.progress(key)
        if (saved != null && (startEp < 0 || saved.ep == startEp) && quickResume(saved)) return
        val last = Store.vodLine(key)
        val order = items.indices.sortedBy { if (items[it].sourceKey == last?.first) 0 else 1 }
        for (i in order) {
            val d = detailOf(i)
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
        curLine = if (last != null && items[curSrc].sourceKey == last.first) {
            first.lines.indexOfFirst { it.name == last.second }.takeIf { it >= 0 } ?: directLine(first)
        } else {
            directLine(first)
        }
        var start = 0L
        val lastEp = maxOf(0, (first.lines.getOrNull(curLine)?.episodes?.size ?: 1) - 1)
        if (startEp >= 0) {
            // Opened on a particular file: that one, picked up where it was left only if it is the one last watched.
            curEp = startEp.coerceIn(0, lastEp)
            if (saved != null && saved.ep == curEp) start = saved.posMs
        } else if (saved != null) {
            curEp = saved.ep.coerceIn(0, lastEp)
            start = saved.posMs
        }
        refreshLists()
        updateFavorite()
        Library.markSeen(key, Tracker.countOf(first))
        needWarm = true
        if (start >= RESUME_TIP_MIN_MS) resumeTipFrom = start
        playCurrent(start)
        prefetchDetails()
    }

    private fun epName(): String = details[curSrc]?.lines?.getOrNull(curLine)?.episodes?.getOrNull(curEp)?.name.orEmpty()

    private fun selectLine(index: Int) {
        if (index < 0) return
        pinned = true
        if (isLive) {
            liveStart = index
            curLine = index
            liveUrls.getOrNull(index)?.let { Store.setLiveLine(title, it) }
        } else {
            // The row picked may be a line of another source: that source plays from now on.
            val (s, li) = lineRefs.getOrNull(index) ?: return
            pickVodLine(s, li)
            return
        }
        refreshLists()
        playCurrent(0)
    }

    /**
     * Where playback is now, to carry to another line of the same episode: 0 when nothing has started yet (then the
     * pending start still applies), or when the episode is about to end anyway.
     */
    private fun carryPosition(): Long {
        if (isLive) return 0
        val p = player ?: return pendingStart
        if (!started) return pendingStart
        saveProgress()
        val pos = p.currentPosition
        val duration = p.duration
        return if (pos > 0 && (duration <= 0 || pos < duration - 5_000)) pos else 0
    }

    private fun selectEpisode(index: Int) {
        if (index < 0) return
        curEp = index
        epGroup = -1 // the range tabs follow the episode now playing again
        refreshLists()
        playCurrent(0)
    }

    /** Clicking a line plays it; the row after the lines opens the panel that lists all of them. */
    private fun onLineClick(index: Int) {
        if (index >= lineCount) showAllLines() else selectLine(index)
    }

    /** Every line in a panel over the page, so the episodes below stay where they are. Picking one closes it. */
    private fun showAllLines() {
        // A snapshot: sources keep loading while the panel is open, and the row picked must be the one shown.
        val refs = lineRefs
        val live = isLive
        var allLines: androidx.appcompat.app.AlertDialog? = null
        val chips = ChipAdapter(fill = true) { index ->
            allLines?.dismiss()
            if (live) selectLine(index) else refs.getOrNull(index)?.let { (s, li) -> pickVodLine(s, li) }
        }
        chips.submit(lineLabels, lineSelected)
        val grid = BoundedRecyclerView(this, panelListHeight()).apply {
            layoutManager = GridLayoutManager(this@PlayActivity, 3)
            adapter = chips
            clipToPadding = false
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("全部线路（${lineLabels.size} 条）")
            .setView(grid)
            .setNegativeButton("关闭", null)
            .show()
        allLines = dialog
        RemoteControl.cover(dialog)
    }

    /**
     * Loads the neighbours of the episode on screen ahead: the next one first, then the previous one (a swipe back) and the
     * one after the next (two quick swipes). Each one's stream address is worked out now and the preload manager fetches its
     * playlist and the first seconds, so that switching to it (autoplay, a swipe, the episode list) does not wait for any of
     * that. It costs little, five seconds of each, loaded once the current one plays steadily, so it is done on mobile data
     * too (unlike the line speed tests, which download megabytes). Not for drive files: their addresses expire.
     */
    private fun preloadNextEpisode() {
        val manager = preloadManager ?: return
        if (isLive || replayMode || isFinishing || allFailed) return
        val episodes = details[curSrc]?.lines?.getOrNull(curLine)?.episodes ?: return
        // On a line that has been stalling, loading ahead would only take bandwidth from the picture: the next episode only,
        // and nothing at all after a few stalls.
        val wanted = when {
            stallPolicy.stalls == 0 -> listOf(curEp + 1, curEp - 1, curEp + 2)
            stallPolicy.stalls <= 2 -> listOf(curEp + 1)
            else -> emptyList()
        }
        val targets = wanted.filter { it in episodes.indices }
            .filter { !QuarkWeb.isLink(episodes[it].url) && !Quark.isLink(episodes[it].url) }
        // Whatever was loaded for other episodes or other lines (the viewer jumped, or changed line) is let go of, and so are
        // sources the player has finished with; what the player is using now stays.
        preloaded.entries.filter { it.key !in targets || it.value.src != curSrc || it.value.line != curLine }.forEach {
            preloaded.remove(it.key)
            if (it.value.item !== playingPreload) manager.remove(it.value.item)
        }
        retired.filter { it !== playingPreload }.forEach { manager.remove(it); retired.remove(it) }
        preloadEpisodes = targets.toSet()
        manager.setCurrentPlayingIndex(curEp)
        manager.invalidate() // what is already added goes on loading (or stops, if its episode is no longer wanted)
        val missing = targets.filter { !preloaded.containsKey(it) }
        if (missing.isEmpty()) return
        val src = curSrc
        val line = curLine
        val now = curEp
        preloadJob?.cancel()
        preloadJob = lifecycleScope.launch {
            for (target in missing) {
                val stream = Resolve.stream(episodes[target].url) ?: continue
                if (curEp != now || curSrc != src || curLine != line) return@launch // the viewer moved on while it was resolving
                val item = mediaFor(stream)
                // A line that is one file for every episode gives the same address (and so the same item) each time.
                if (preloaded.any { it.key != target && it.value.item == item }) continue
                preloaded.remove(target)?.let { manager.remove(it.item) }
                preloaded[target] = Preloaded(src, line, stream, extSubtitle, item)
                manager.add(item, target)
                manager.setCurrentPlayingIndex(now)
                manager.invalidate()
                Log.d("TVP", "preloading episode ${target + 1}: $stream")
            }
        }
    }

    /** The tall full screen as a feed of episodes, like a short-video app: up for the next one, down for the previous. */
    private fun stepEpisode(step: Int) {
        val count = details[curSrc]?.lines?.getOrNull(curLine)?.episodes?.size ?: 0
        val target = curEp + step
        when {
            count <= 1 -> flashHud("这部只有一集", 1_200)
            target < 0 -> flashHud("已经是第一集", 1_200)
            target >= count -> flashHud("已经是最后一集", 1_200)
            else -> {
                selectEpisode(target)
                flashHud(epName(), 1_000)
            }
        }
    }

    /** Room for the list inside the episode / line panels: a share of the screen, so title and buttons always fit around it. */
    private fun panelListHeight() = (resources.displayMetrics.heightPixels * 0.55f).toInt()

    /** The tall full screen has no episode list under the picture: every episode in a panel over it, and the lines one tap away. */
    private fun showEpisodes() {
        val episodes = details[curSrc]?.lines?.getOrNull(curLine)?.episodes ?: return
        var close: () -> Unit = {}
        val chips = ChipAdapter(fill = true) { index ->
            close()
            if (index != curEp) selectEpisode(index)
        }
        val names = episodes.map { Regex("^第0*(\\d+)[集期话]$").find(it.name)?.groupValues?.get(1) ?: it.name }
        chips.submit(names, curEp)
        if (drawers) {
            // 横屏：右侧抽屉，画面留在左边（腾讯视频、B 站横屏的选集）
            val list = RecyclerView(this).apply {
                layoutManager = GridLayoutManager(this@PlayActivity, episodeSpan(names, 400 - 32 - 8, 5))
                adapter = chips
                clipToPadding = false
                setPadding(dp(16), dp(4), dp(16), dp(8))
                layoutManager?.scrollToPosition(curEp)
            }
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView((layoutInflater.inflate(R.layout.button_tonal, this, false) as MaterialButton).apply {
                    text = "换线路"
                    setOnClickListener { close(); showAllLines() }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dp(20), dp(8), dp(20), 0)
                })
            }
            val side = showSidePanel("选集（共 ${episodes.size} 集）", box, width = 400)
            close = { side.dismiss() }
            return
        }
        val grid = BoundedRecyclerView(this, panelListHeight()).apply {
            layoutManager = GridLayoutManager(this@PlayActivity, episodeSpan(names, resources.configuration.screenWidthDp - 48 - 32, 5))
            adapter = chips
            clipToPadding = false
            setPadding(dp(16), dp(8), dp(16), dp(8))
            layoutManager?.scrollToPosition(curEp)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("选集（共 ${episodes.size} 集）")
            .setView(grid)
            .setNeutralButton("线路") { _, _ -> showAllLines() }
            .setNegativeButton("关闭", null)
            .show()
        close = { dialog.dismiss() }
        RemoteControl.cover(dialog)
    }

    /** Plays one line of one source, from the all-lines panel. */
    private fun pickVodLine(s: Int, li: Int) {
        val d = details[s] ?: return
        // Taken (and the progress saved) before the source changes, so it belongs to the line that was playing.
        val at = carryPosition()
        val epBefore = curEp
        pinned = true
        curSrc = s
        curLine = li
        curEp = curEp.coerceAtMost(maxOf(0, (d.lines.getOrNull(li)?.episodes?.size ?: 1) - 1))
        refreshLists()
        // Another line of the same episode goes on from where this one is; a different episode starts from the top.
        playCurrent(if (curEp == epBefore) at else 0)
    }

    /** The line rows stay one row that scrolls sideways; a last row opens the panel when there are more than a few. */
    private fun submitLines(labels: List<String>, selected: Int) {
        lineCount = labels.size
        lineLabels = labels
        lineSelected = selected
        val more = if (labels.size > LINES_FOLD) listOf("全部 ${labels.size} 条 ▾") else emptyList()
        lineAdapter.submit(labels + more, selected)
    }

    private fun refreshLists() {
        if (isLive) {
            submitLines(liveUrls.indices.map { "线路${it + 1}" + (lineQuality["live:$it"]?.let { q -> " $q" } ?: "") }, curLine)
            return
        }
        // The lines of every loaded source in one list, each with its source's name; picking one switches to that source.
        val refs = ArrayList<Pair<Int, Int>>()
        val labels = ArrayList<String>()
        for (s in items.indices) {
            val lines = details[s]?.lines ?: continue
            val names = lines.map { it.name }
            lines.forEachIndexed { i, line ->
                val name = if (names.count { it == line.name } > 1) "${line.name} ${i + 1}" else line.name
                val label = "${items[s].sourceName} · $name"
                labels += lineQuality["$s:$i"]?.let { q -> "$label · $q" } ?: label
                refs += s to i
            }
        }
        lineRefs = refs
        submitLines(labels, refs.indexOf(curSrc to curLine))
        val d = details[curSrc] ?: return
        fillDetail()
        val episodes = d.lines.getOrNull(curLine)?.episodes ?: emptyList()
        val groups = findViewById<RecyclerView?>(R.id.epGroups)
        if (groups != null && episodes.size > EP_GROUP) {
            val count = (episodes.size + EP_GROUP - 1) / EP_GROUP
            val g = (if (epGroup in 0 until count) epGroup else curEp / EP_GROUP).coerceIn(0, count - 1)
            epOffset = g * EP_GROUP
            groupAdapter.submit((0 until count).map { "${it * EP_GROUP + 1}-${minOf((it + 1) * EP_GROUP, episodes.size)}" }, g)
            groups.visibility = View.VISIBLE
        } else {
            epOffset = 0
            groups?.visibility = View.GONE
        }
        val shown = episodes.drop(epOffset).take(if (groups != null) EP_GROUP else episodes.size)
        // TV: "第03集" reads as just "3" on a tile, like TV video apps; other names stay as they are.
        val label = { n: String -> if (isTv) Regex("^第0*(\\d+)[集期话]$").find(n)?.groupValues?.get(1) ?: n else n }
        epAdapter.submit(shown.map { label(it.name) }, curEp - epOffset)
        applyEpisodeSpan(shown.map { label(it.name) })
        epLabel?.text = "选集（共 ${episodes.size} 集）"
        updateOverlayTitle()
        episodesView?.scrollToPosition((curEp - epOffset).coerceAtLeast(0))
    }

    /**
     * 选集网格几列：「第12集」这种短名排满 [max] 列；综艺的「20261022-01」这类长名少排几列，免得每格都只剩「202610…」。
     * 按 14sp 估宽：汉字约 14dp，数字和字母约 8dp，再加格子的内边距和间距。
     */
    private fun episodeSpan(labels: List<String>, widthDp: Int, max: Int): Int {
        val widest = labels.maxOfOrNull { l -> l.sumOf { c -> if (c.code > 0x2E80) 14.0 else 8.0 } } ?: 0.0
        val cell = widest + 28 + 8 // item_chip：左右内边距各 14dp，外边距各 4dp
        return (widthDp / cell).toInt().coerceIn(1, max)
    }

    /** 选集列表的列数；列表还没量出宽度（刚换布局）时等它排好再算，免得拿整屏宽度估多了 */
    private fun applyEpisodeSpan(labels: List<String>) {
        val list = episodesView ?: return
        val lm = list.layoutManager as? GridLayoutManager ?: return
        val max = if (panel != null) 3 else 4
        if (list.width == 0) {
            list.post { if (list.width > 0 && list.layoutManager === lm) applyEpisodeSpan(labels) }
            return
        }
        val width = ((list.width - list.paddingLeft - list.paddingRight) / resources.displayMetrics.density).toInt()
        lm.spanCount = episodeSpan(labels, width, max)
    }

    private fun setInfo(text: String) {
        infoText = text
        tvInfo?.text = text
    }

    private fun saveProgress() {
        if (isLive || !started || key.isEmpty() || details[curSrc] == null) return
        val p = player ?: return
        val pos = p.currentPosition
        val nearEnd = p.duration > 0 && pos > p.duration - 15_000
        val entry = Library.Entry(key, title, pic, items, curEp, epName(), if (nearEnd) 0 else pos, durMs = p.duration.coerceAtLeast(0))
        Library.saveProgress(entry)
        // The launcher's provider is a cross-process call: keep it off the main thread.
        val duration = p.duration
        lifecycleScope.launch(Dispatchers.IO) { WatchNext.publish(this@PlayActivity, entry, duration) }
    }

    // ---- favorites -----------------------------------------------------------------------------------------------

    private fun toggleFavorite() {
        val count = details[curSrc]?.let { Tracker.countOf(it) } ?: 0
        val follow = !Library.isFavorite(key)
        Library.setFavorite(Library.Entry(key, title, pic, items, seen = count, latest = count), follow)
        if (follow) askNotifications()
        updateFavorite()
    }

    /** New episodes are announced by notification: ask once, when the user starts following a series. */
    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8)
        }
    }

    private fun updateFavorite() {
        val button = favoriteBtn ?: return
        if (isLive) return
        val fav = Library.isFavorite(key)
        button.text = getString(if (fav) R.string.favorited else R.string.favorite)
        button.setIconResource(if (fav) R.drawable.ic_heart else R.drawable.ic_heart_outline)
        button.iconTint = ColorStateList.valueOf(getColor(if (fav) R.color.heart else R.color.on_surface))
    }

    // ---- skip intro / outro, sleep timer, once-a-second housekeeping ---------------------------------------------

    private val ticker = object : Runnable {
        override fun run() {
            tick++
            if (sleepAtMs > 0 && SystemClock.elapsedRealtime() >= sleepAtMs) sleepNow("睡眠定时到了，已暂停")
            val p = player
            if (!isLive && started && p != null && !outroDone) {
                val outro = Store.skip(key).second
                val duration = p.duration
                if (outro > 0 && duration > (outro + 60) * 1000L && p.currentPosition >= duration - outro * 1000L) {
                    outroDone = true
                    val count = details[curSrc]?.lines?.getOrNull(curLine)?.episodes?.size ?: 0
                    if (sleepAfterEp) {
                        sleepNow("这一集播完了，已按睡眠定时停止")
                    } else if (curEp + 1 < count && !nextCancelled) {
                        flashHud("已跳过片尾，播放下一集")
                        selectEpisode(curEp + 1)
                    }
                }
            }
            if (overlay && panel?.visibility == View.VISIBLE) updateProgress()
            updateNextCard()
            if (vfullShown) syncThinProgress()
            if (tick % 10 == 0 && findViewById<View?>(R.id.landTop)?.visibility == View.VISIBLE) updateClock()
            // The programme guide moves on every few minutes.
            if (isLive && started && tick % 30 == 0) setInfo(playingText())
            handler.postDelayed(this, 1_000)
        }
    }

    private fun skipIntro(resumed: Boolean) {
        if (introDone) return
        introDone = true
        val intro = Store.skip(key).first
        val p = player ?: return
        if (!resumed && intro > 0 && p.duration > (intro + 60) * 1000L && p.currentPosition < intro * 1000L) {
            Log.d("TVP", "skip intro: ${intro}s, speed ${p.playbackParameters.speed}")
            p.seekTo(intro * 1000L)
            flashHud("已跳过片头 $intro 秒")
        }
    }

    private fun sleepNow(message: String) {
        sleepAtMs = 0
        sleepAfterEp = false
        sleepChoice = 0
        player?.pause()
        stopKeepAlive()
        flashHud(message, 4_000)
        toast(message)
    }

    // ---- live: remembered line, channel zapping ------------------------------------------------------------------

    /** A line the user once picked by hand for this channel goes first, and skips the speed test. */
    private fun applyRememberedLine() {
        val idx = Store.liveLine(title)?.let { liveUrls.indexOf(it) } ?: -1
        // Not picked by hand: start from the line that worked last time (still re-tested if it turns slow).
        val lastGood = if (idx < 0) Store.liveLastGood(title)?.let { liveUrls.indexOf(it) } ?: -1 else -1
        liveStart = maxOf(idx, lastGood, 0)
        pinned = idx >= 0
    }

    /** The line that just started is tried first next time: per channel for live, source + line per title on demand. */
    private fun rememberLine() {
        val c = current ?: return
        if (isLive) Store.setLiveLastGood(title, c.url)
        else if (key.isNotEmpty() && c.src in items.indices) {
            val lineName = details[c.src]?.lines?.getOrNull(c.line)?.name ?: c.label.substringAfter(" · ")
            Store.setVodLine(key, items[c.src].sourceKey, lineName)
            // A drive address expires within hours: replaying it next time would only fail first.
            if (!Quark.isLink(c.url)) resolvedUrl?.let { Store.setVodLast(key, Store.VodLast(items[c.src].sourceKey, c.line, lineName, curEp, c.url, it, System.currentTimeMillis())) }
        }
    }

    private fun channelList(): List<LiveChannel> {
        if (groupChannels.isEmpty()) groupChannels = Store.channels().filter { it.group == liveGroup }
        return groupChannels
    }

    private fun switchChannel(delta: Int) {
        val list = channelList()
        if (list.size < 2) return
        val at = list.indexOfFirst { it.name == title }.coerceAtLeast(0)
        openChannel(list[(at + delta + list.size) % list.size], list.size, (at + delta + list.size) % list.size + 1)
    }

    private fun openChannel(channel: LiveChannel, total: Int, number: Int) {
        handler.removeCallbacks(rescue)
        gen++
        probeJobs.forEach { it.cancel() } // the old channel's speed tests would only compete for bandwidth
        probeJobs = emptyList()
        title = channel.name
        liveUrls = channel.urls
        catchup = channel.catchup
        lineQuality.clear()
        current = null
        started = false
        resolution = ""
        findViewById<TextView?>(R.id.title)?.text = title
        updateOverlayTitle()
        applyRememberedLine()
        needWarm = true
        refreshLists()
        flashHud("$number/$total  ${channel.name}", 1_500)
        playCurrent(0)
    }

    private val commitDigits = Runnable {
        val n = digits.toIntOrNull()
        digits = ""
        if (n == null) return@Runnable
        val list = channelList()
        val target = list.firstOrNull { it.name.equals("CCTV$n", true) || it.name.equals("CCTV-$n", true) } ?: list.getOrNull(n - 1)
        if (target == null) flashHud("没有 $n 台") else openChannel(target, list.size, list.indexOf(target) + 1)
    }

    private fun typeDigit(d: Int) {
        if (digits.length >= 3) digits = ""
        digits += d
        flashHud("频道 $digits", 1_500)
        handler.removeCallbacks(commitDigits)
        handler.postDelayed(commitDigits, 1_500)
    }

    // ---- playback settings dialog -------------------------------------------------------------------------------

    /** A − / + button: a tap is one step, holding it keeps stepping (also with the remote's OK key held). */
    private fun stepper(button: View, step: () -> Unit) {
        button.setOnClickListener { step() }
        val repeat = object : Runnable {
            override fun run() {
                if (!button.isPressed) return
                step()
                handler.postDelayed(this, 120)
            }
        }
        button.setOnLongClickListener {
            handler.post(repeat)
            true
        }
    }

    /** One segmented row of equal-width options; [selected] < 0 leaves none picked. */
    private fun fill(group: MaterialButtonToggleGroup, labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
        group.removeAllViews()
        labels.forEach { label ->
            val button = layoutInflater.inflate(R.layout.toggle_option, group, false) as MaterialButton
            button.id = View.generateViewId()
            button.text = label
            group.addView(button)
        }
        if (selected >= 0) group.check(group.getChildAt(selected).id)
        group.addOnButtonCheckedListener { g, id, checked ->
            if (checked) {
                val index = (0 until g.childCount).indexOfFirst { g.getChildAt(it).id == id }
                if (index >= 0) onPick(index)
            }
        }
    }

    private fun showPlaySettings() {
        val view = layoutInflater.inflate(R.layout.dialog_play_settings, null)

        // speed (on demand only)
        val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f, 3f)
        if (isLive) view.findViewById<View>(R.id.speedBox).visibility = View.GONE else {
            val now = player?.playbackParameters?.speed ?: Store.playSpeed()
            fill(view.findViewById(R.id.speedGroup), speeds.map { "${it}×".replace(".0×", "×") }, speeds.indexOfFirst { abs(it - now) < 0.01f }) {
                player?.setPlaybackSpeed(speeds[it])
                Store.setPlaySpeed(speeds[it])
            }
        }

        // picture: letterbox, crop to fill, or stretch
        val fitModes = listOf("fit", "zoom", "fill")
        fill(view.findViewById(R.id.fitGroup), listOf("适应", "填满", "拉伸"), fitModes.indexOf(Store.resizeMode()).coerceAtLeast(0)) {
            Store.setResizeMode(fitModes[it])
            playerView.resizeMode = resizeModeOf(fitModes[it])
        }

        // subtitles and audio tracks of the stream now playing, and subtitle files from the storage
        val subtitleValue = view.findViewById<TextView>(R.id.subtitleValue)
        val audioBox = view.findViewById<View>(R.id.audioBox)
        val audioValue = view.findViewById<TextView>(R.id.audioValue)
        fun showTracks() {
            subtitleValue.text = trackValue(C.TRACK_TYPE_TEXT, trackOptions(C.TRACK_TYPE_TEXT), "关闭")
            val audio = trackOptions(C.TRACK_TYPE_AUDIO)
            audioBox.visibility = if (audio.size > 1) View.VISIBLE else View.GONE
            audioValue.text = trackValue(C.TRACK_TYPE_AUDIO, audio, "")
        }
        showTracks()
        subtitleValue.setOnClickListener { chooseTrack(C.TRACK_TYPE_TEXT, "字幕", ::showTracks) }
        audioValue.setOnClickListener { chooseTrack(C.TRACK_TYPE_AUDIO, "音轨", ::showTracks) }
        fill(view.findViewById(R.id.subSizeGroup), listOf("小", "标准", "大", "特大"), Store.subtitleSize()) {
            Store.setSubtitleSize(it)
            applySubtitleStyle()
        }
        // 时间偏移只对导入的字幕：片源自带的字幕没法改时间轴
        if (extSubtitleBase != null) {
            view.findViewById<View>(R.id.subOffsetBox).visibility = View.VISIBLE
            val offsetLabel = view.findViewById<TextView>(R.id.subOffsetLabel)
            fun step(delta: Long) {
                subOffsetMs = (subOffsetMs + delta).coerceIn(-MAX_SUB_OFFSET_MS, MAX_SUB_OFFSET_MS)
                offsetLabel.text = subOffsetText()
                handler.removeCallbacks(applySubOffset)
                handler.postDelayed(applySubOffset, 800)
            }
            offsetLabel.text = subOffsetText()
            stepper(view.findViewById(R.id.subOffsetMinus)) { step(-SUB_STEP_MS) }
            stepper(view.findViewById(R.id.subOffsetPlus)) { step(SUB_STEP_MS) }
        }
        view.findViewById<View>(R.id.importSub).setOnClickListener {
            try {
                pickSubtitle.launch(arrayOf("*/*"))
            } catch (e: ActivityNotFoundException) {
                toast("这台设备没有文件选择器，没法导入字幕")
            }
        }

        // 离线下载：手机、平板上的点播（网盘和网络存储的文件本来就能直接看，不下）
        view.findViewById<View>(R.id.downloadEp).apply {
            val fromDrive = items.any { it.sourceKey == Quark.SOURCE_KEY || it.sourceKey == QuarkWeb.SOURCE_KEY || it.sourceKey == NetStore.SOURCE_KEY }
            visibility = if (!isTv && !isLive && !replayMode && !fromDrive) View.VISIBLE else View.GONE
            setOnClickListener { downloadEpisode() }
        }

        // programme replay: only for a live channel that offers playback of past programmes
        view.findViewById<View>(R.id.replayBox).visibility = if (isLive && catchup != null) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.replayOpen).setOnClickListener { showReplay() }

        // skip intro / outro (per series)
        if (isLive || key.isEmpty()) view.findViewById<View>(R.id.skipBox).visibility = View.GONE else {
            var (intro, outro) = Store.skip(key)
            val introLabel = view.findViewById<TextView>(R.id.introLabel)
            val outroLabel = view.findViewById<TextView>(R.id.outroLabel)
            fun update() {
                introLabel.text = if (intro == 0) "不跳过" else "$intro 秒"
                outroLabel.text = if (outro == 0) "不跳过" else "$outro 秒"
                Store.setSkip(key, intro, outro)
            }
            update()
            stepper(view.findViewById(R.id.introMinus)) { intro = (intro - 5).coerceAtLeast(0); update() }
            stepper(view.findViewById(R.id.introPlus)) { intro = (intro + 5).coerceAtMost(MAX_SKIP); update() }
            stepper(view.findViewById(R.id.outroMinus)) { outro = (outro - 5).coerceAtLeast(0); update() }
            stepper(view.findViewById(R.id.outroPlus)) { outro = (outro + 5).coerceAtMost(MAX_SKIP); update() }
        }

        // sleep timer
        val sleepStatus = view.findViewById<TextView>(R.id.sleepStatus)
        fun sleepText() {
            sleepStatus.text = when {
                sleepAfterEp -> "播完这一集后停止"
                sleepAtMs > 0 -> "还剩约 ${maxOf(1, (sleepAtMs - SystemClock.elapsedRealtime()) / 60_000 + 1)} 分钟后暂停"
                else -> "未开启"
            }
        }
        sleepText()
        val sleepLabels = if (isLive) listOf("关闭", "15 分", "30 分", "60 分", "90 分") else listOf("关闭", "15 分", "30 分", "60 分", "播完本集")
        fill(view.findViewById(R.id.sleepGroup), sleepLabels, sleepChoice) { i ->
            sleepChoice = i
            sleepAtMs = 0
            sleepAfterEp = false
            when {
                i == 0 -> Unit
                !isLive && i == 4 -> sleepAfterEp = true
                else -> sleepAtMs = SystemClock.elapsedRealtime() + listOf(0, 15, 30, 60, 90)[i] * 60_000L
            }
            sleepText()
        }

        // leaving the app (phones and tablets)
        val canPip = !isTv && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        if (isTv) view.findViewById<View>(R.id.leaveBox).visibility = View.GONE else {
            val actions = if (canPip) listOf("pause", "audio", "pip") else listOf("pause", "audio")
            val labels = if (canPip) listOf("暂停", "后台听声音", "小窗播放") else listOf("暂停", "后台听声音")
            fill(view.findViewById(R.id.leaveGroup), labels, actions.indexOf(Store.leaveAction()).coerceAtLeast(0)) {
                Store.setLeaveAction(actions[it])
                if (actions[it] == "audio" && Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
            }
            view.findViewById<View>(R.id.pipNow).apply {
                visibility = if (canPip) View.VISIBLE else View.GONE
                setOnClickListener { enterPip() }
            }
        }

        if (isTv || drawers) {
            showSidePanel("播放设置", view)
            return
        }
        // Phones and tablets: a bottom sheet, within thumb reach and over less of the picture.
        val sheet = BottomSheetDialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(BottomSheetDragHandleView(this@PlayActivity))
            addView(TextView(this@PlayActivity).apply {
                text = "播放设置"
                setTextAppearance(R.style.TextAppearance_TvPlayer_Headline)
                setPadding(dp(24), 0, dp(24), dp(8))
            })
            addView(view)
            setPadding(0, 0, 0, dp(16))
        }
        sheet.setContentView(box)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        RemoteControl.cover(sheet)
        sheet.show()
    }

    /** The whole synopsis (the details page shows three lines). */
    private fun showSynopsis() {
        val d = details[curSrc] ?: return
        if (d.content.isEmpty()) return
        val text = TextView(this).apply {
            text = d.content
            setTextAppearance(R.style.TextAppearance_TvPlayer_Body)
            setLineSpacing(0f, 1.3f)
            setPadding(dp(24), 0, dp(24), dp(16))
        }
        showSidePanel(d.title.ifEmpty { title }, android.widget.ScrollView(this).apply { isFocusable = true; addView(text) }, scrollOnly = true)
    }

    /** TV: a full-height panel on the right; the picture stays visible on the left. Back closes it. */
    /** 手机横屏全屏也用它：底部面板在横屏里又矮又挡画面，腾讯视频、B 站横屏的倍速、选集、设置都是右侧抽屉 */
    private fun showSidePanel(title: String, content: View, scrollOnly: Boolean = false, width: Int = 420): android.app.Dialog {
        val dialog = android.app.Dialog(this, R.style.Theme_TvPlayer_SidePanel)
        // With buttons inside, the scroll container must not take focus (it would draw a highlight); with plain text
        // ([scrollOnly]) it has to, or the remote could not scroll it.
        if (!scrollOnly) (content as? ViewGroup)?.apply {
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // TV overscan safe area on the top, right and bottom; a phone only needs to keep clear of the camera cut-out
            val (v, end) = if (isTv) dp(27) to dp(24) else dp(16) to dp(8)
            setPadding(0, v, end, v)
            if (!isTv) ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
                view.setPadding(0, v, end + cut.right, v)
                insets
            }
            addView(TextView(this@PlayActivity).apply {
                text = title
                setTextAppearance(R.style.TextAppearance_TvPlayer_Headline)
                setPadding(dp(24), 0, dp(24), dp(12))
            })
            addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        dialog.setContentView(root)
        dialog.window?.apply {
            setGravity(Gravity.END)
            setLayout(dp(width), ViewGroup.LayoutParams.MATCH_PARENT)
            // Full height like the picture behind it: no strip left where the hidden status bar would be.
            WindowCompat.getInsetsController(this, decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
            attributes = attributes.also { it.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        }
        dialog.setOnShowListener {
            // Start on the first option so the remote is immediately useful.
            content.focusSearch(View.FOCUS_DOWN)?.requestFocus() ?: content.requestFocus()
        }
        dialog.setCanceledOnTouchOutside(true) // 手机上点左边的画面就收起
        dialog.show()
        return dialog
    }

    /** Only when it was started: stopService is a call into the system, not free on every resume. */
    private fun stopKeepAlive() {
        KeepAliveService.onStop = null
        if (!keepAlive) return
        keepAlive = false
        KeepAliveService.stop(this)
    }

    // ---- picture in picture / background sound -------------------------------------------------------------------

    private fun enterPip(): Boolean {
        if (isTv || !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        pipRequested = true
        return try {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()).also { if (!it) pipRequested = false }
        } catch (e: Exception) {
            Log.d("TVP", "pip failed: $e")
            pipRequested = false
            false
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        leaving = true
        if (Store.leaveAction() == "pip" && player?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipRequested = isInPictureInPictureMode
        applyPip(isInPictureInPictureMode)
    }

    /** In the small floating window only the picture is left. */
    private fun applyPip(inPip: Boolean) {
        if (inPip) nextCard?.visibility = View.GONE
        val gone = if (inPip) View.GONE else View.VISIBLE
        for (id in listOf(R.id.topbar, R.id.content, R.id.back, R.id.title, R.id.cast, R.id.playSettings, R.id.landOverlay)) findViewById<View?>(id)?.visibility = gone
        if (inPip) resumeTip?.visibility = View.GONE
        // The tall full screen's episode button is shown only when there is more than one episode: refreshLists decides.
        findViewById<View?>(R.id.vOverlay)?.visibility = if (!inPip && vfullShown) View.VISIBLE else View.GONE
        if (!inPip) refreshLists()
        syncPullArrow()
        playerView.useController = !inPip
        if (inPip) playerView.hideController()
    }

    private fun setVideoEnabled(enabled: Boolean) {
        val p = player ?: return
        noteDisturbance() // the video renderer restarts, and the player buffers for a moment
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !enabled).build()
    }

    // ---- picture, subtitles, audio tracks, programme replay -------------------------------------------------------

    private fun resizeModeOf(mode: String) = when (mode) {
        "zoom" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        "fill" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    /** Files can be seeked, and so can a replayed programme; a live stream has no timeline. */
    private fun canSeek() = !isLive || replayMode

    /** The item for one stream: the type hint, and the subtitle file the user imported, if any. */
    private fun mediaFor(stream: String): MediaItem = MediaItem.Builder().setUri(stream).apply {
        if (stream.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        extSubtitle?.let { uri ->
            val subtitle = MediaItem.SubtitleConfiguration.Builder(uri)
                .setMimeType(extSubtitleMime)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            setSubtitleConfigurations(listOf(subtitle))
        }
    }.build()

    /** Restarts the stream now playing so a subtitle file just added shows, from the same place. */
    private fun reloadStream() {
        val stream = resolvedUrl ?: return
        val p = player ?: return
        val at = if (isLive && !replayMode) C.TIME_UNSET else p.currentPosition
        noteDisturbance()
        p.setMediaItem(mediaFor(stream), at)
        p.prepare()
    }

    /** A subtitle file picked from the storage: copied as UTF-8 and loaded into the picture. */
    private fun importSubtitle(source: Uri) {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { Subtitles.load(this@PlayActivity, source) }
            if (loaded == null) {
                toast("字幕文件读不了")
                return@launch
            }
            val (name, file) = loaded
            val mime = Subtitles.mimeOf(name)
            if (mime == null) {
                toast("只支持 srt、vtt、ass 字幕")
                return@launch
            }
            extSubtitle = file
            extSubtitleBase = file
            extSubtitleMime = mime
            subOffsetMs = 0
            handler.removeCallbacks(applySubOffset)
            // Subtitles may have been turned off by hand: the new one must show.
            player?.let { p -> p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build() }
            reloadStream()
            toast("已加载字幕：$name")
        }
    }

    /** A subtitle or audio track of the picture now playing, and where the player selects it. */
    private class TrackOption(val label: String, val group: Tracks.Group, val index: Int)

    private fun trackOptions(type: Int): List<TrackOption> {
        val out = ArrayList<TrackOption>()
        for (g in player?.currentTracks?.groups.orEmpty()) {
            if (g.type != type || !g.isSupported) continue
            for (i in 0 until g.length) {
                val fallback = (if (type == C.TRACK_TYPE_TEXT) "字幕 " else "音轨 ") + (out.size + 1)
                out += TrackOption(trackName(g.getTrackFormat(i), fallback), g, i)
            }
        }
        return out
    }

    private fun trackName(format: Format, fallback: String): String {
        format.label?.takeIf { it.isNotBlank() }?.let { return it }
        val language = format.language?.takeIf { it.isNotBlank() && it != "und" } ?: return fallback
        return Locale.forLanguageTag(language).getDisplayName(Locale.SIMPLIFIED_CHINESE).ifBlank { fallback }
    }

    /** The index in [options] of the track in use, -1 when none is (subtitles turned off). */
    private fun selectedTrack(type: Int, options: List<TrackOption>): Int {
        val p = player ?: return -1
        if (type == C.TRACK_TYPE_TEXT && type in p.trackSelectionParameters.disabledTrackTypes) return -1
        return options.indexOfFirst { it.group.isTrackSelected(it.index) }
    }

    private fun trackValue(type: Int, options: List<TrackOption>, none: String): String =
        selectedTrack(type, options).let { if (it >= 0) options[it].label else none }

    /** Shows the tracks as a list; for subtitles the first row turns them off. */
    private fun chooseTrack(type: Int, title: String, changed: () -> Unit) {
        val options = trackOptions(type)
        val current = selectedTrack(type, options)
        val subtitles = type == C.TRACK_TYPE_TEXT
        val labels = (if (subtitles) listOf("关闭") else emptyList()) + options.map { it.label }
        val checked = if (subtitles) current + 1 else current.coerceAtLeast(0)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { picked, which ->
                applyTrack(type, options.getOrNull(if (subtitles) which - 1 else which))
                picked.dismiss()
                changed()
            }
            .setNegativeButton("取消", null)
            .show()
        RemoteControl.cover(dialog)
    }

    /** [option] null means subtitles off. Audio always has a track picked. */
    private fun applyTrack(type: Int, option: TrackOption?) {
        val p = player ?: return
        noteDisturbance()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().apply {
            when {
                option != null -> setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, option.index))
                type == C.TRACK_TYPE_TEXT -> setTrackTypeDisabled(type, true)
                else -> return
            }
        }.build()
    }

    /** The past programmes of this channel that can be replayed, and "回到直播" to leave the replay. */
    private fun showReplay() {
        val c = catchup ?: return
        lifecycleScope.launch {
            val shows = Epg.aired(title, c.days.coerceIn(1, CatchupUrl.MAX_DAYS)).take(MAX_SHOWS)
            if (shows.isEmpty()) {
                toast("节目单里没有可以回看的节目")
                return@launch
            }
            val labels = listOf("回到直播") + shows.map { "${Epg.stamp(it.startMs)}  ${it.title}" }
            val dialog = MaterialAlertDialogBuilder(this@PlayActivity)
                .setTitle("节目回看")
                .setItems(labels.toTypedArray()) { _, which -> if (which == 0) backToLive() else playReplay(shows[which - 1]) }
                .setNegativeButton("取消", null)
                .show()
            RemoteControl.cover(dialog)
        }
    }

    /** Plays one past programme from its start to its end, in place of the live picture of the channel. */
    private fun playReplay(show: Epg.Slot) {
        val c = catchup ?: return
        val url = CatchupUrl.build(c.template, show.startMs, show.endMs)
        replayMode = true
        gen++
        setQuietControls(false) // 回看不走 attempt，加载中切过来时别留着「不自动弹」
        handler.removeCallbacks(stall)
        handler.removeCallbacks(rescue)
        probeJobs.forEach { it.cancel() }
        probeJobs = emptyList()
        playJob?.cancel()
        started = false
        pendingStart = 0
        resolution = ""
        current = Cand(url, "回看 ${Epg.clock(show.startMs)} ${show.title}")
        resolvedUrl = url
        setInfo("正在连接回看：${show.title}")
        player?.apply {
            setMediaItem(mediaFor(url))
            prepare()
        }
    }

    /** Leaves a replayed programme for the live picture of the same channel. */
    private fun backToLive() {
        if (!replayMode) return
        applyRememberedLine()
        needWarm = true
        refreshLists()
        playCurrent(0)
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

    private val hideHud = Runnable { hud?.hide() }

    /** [icon] 不为 0 时前面带图标；[progress] 在 0..1 之间时下面带一条进度条。 */
    private fun flashHud(text: String, ms: Long = 900, icon: Int = 0, progress: Float = -1f) {
        hud?.show(text, icon, progress)
        handler.removeCallbacks(hideHud)
        handler.postDelayed(hideHud, ms)
    }

    /** With the picture focused and the controller hidden: left/right seek 10s, up/down jump to the side panel. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 按住左右键快进，松手时才真正跳过去
        if (seekPending >= 0 && event.action == KeyEvent.ACTION_UP &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) {
            finishHeldSeek()
            return true
        }
        // Remote media keys (the picture has no on-screen controller to forward them).
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val p = player
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> { p?.let { if (it.isPlaying) it.pause() else it.play() }; return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { p?.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { p?.pause(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    if (canSeek() && p != null && p.duration > 0) {
                        val delta = if (event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) 30_000L else -30_000L
                        val target = (p.currentPosition + delta).coerceIn(0L, p.duration)
                        p.seekTo(target)
                        flashHud(if (delta > 0) "快进 30 秒  ${formatTime(target)}" else "快退 30 秒  ${formatTime(target)}", icon = seekIcon(delta > 0), progress = target.toFloat() / p.duration)
                    }
                    return true
                }
            }
        }
        // Live: channel keys and number keys work from anywhere.
        if (isLive && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val code = event.keyCode
            when {
                code == KeyEvent.KEYCODE_CHANNEL_UP -> { switchChannel(1); return true }
                code == KeyEvent.KEYCODE_CHANNEL_DOWN -> { switchChannel(-1); return true }
                code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { typeDigit(code - KeyEvent.KEYCODE_0); return true }
                code in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> { typeDigit(code - KeyEvent.KEYCODE_NUMPAD_0); return true }
            }
        }
        if (overlay && event.action == KeyEvent.ACTION_DOWN) {
            if (panel?.visibility == View.VISIBLE) {
                bumpPanel() // the user is using the bar: keep it up
            } else {
                // On demand the bar opens with DOWN (or MENU), and OK on the bare picture plays / pauses. Live has no
                // play / pause and DOWN is the previous channel there, so OK opens the bar on live.
                val show = when (event.keyCode) {
                    KeyEvent.KEYCODE_MENU -> true
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> isLive
                    KeyEvent.KEYCODE_DPAD_DOWN -> !isLive
                    else -> false
                }
                // 「下一集」卡片在的时候，确认键就是立即播放
                if (event.keyCode in OK_KEYS && nextCard?.visibility == View.VISIBLE) {
                    if (event.repeatCount == 0) playNextNow()
                    return true
                }
                if (!isLive && event.keyCode in OK_KEYS) {
                    // Holding OK repeats the key: only the first press toggles. playWhenReady (not isPlaying) so a press
                    // while buffering pauses instead of "playing" again.
                    if (event.repeatCount == 0) player?.let { p ->
                        if (p.playWhenReady) { p.pause(); flashHud("已暂停", 1200) } else { p.play(); flashHud("播放", 700) }
                    }
                    return true
                }
                if (show) {
                    setPanelVisible(true)
                    return true
                }
            }
        }
        // The release of OK on the bare picture is swallowed too, so the player view does not act on it as well.
        if (overlay && !isLive && panel?.visibility != View.VISIBLE && event.action == KeyEvent.ACTION_UP && event.keyCode in OK_KEYS) return true
        // With the TV bar hidden the picture is all there is, whatever currently holds focus.
        val onPicture = playerView.hasFocus() || (overlay && panel?.visibility != View.VISIBLE)
        if (overlay && event.action == KeyEvent.ACTION_DOWN && onPicture && !playerView.isControllerFullyVisible) {
            val toPanel = {
                listOfNotNull(favoriteBtn, findViewById(R.id.cast), findViewById(R.id.playSettings))
                    .firstOrNull { it.visibility == View.VISIBLE }?.requestFocus()
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (canSeek()) {
                        player?.takeIf { it.duration > 0 }?.let { p ->
                            val forward = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                            if (event.repeatCount == 0) {
                                finishHeldSeek() // 换了方向：先把上一次按住攒下的跳完
                                seekFrom = p.currentPosition
                                seekLastAt = event.eventTime
                                seekDownAt = event.downTime
                                val delta = if (forward) SeekRamp.TAP_MS else -SeekRamp.TAP_MS
                                val target = (p.currentPosition + delta).coerceIn(0L, maxOf(0L, p.duration))
                                p.seekTo(target)
                                playerView.hideController()
                                flashHud(if (delta > 0) "快进 10 秒  ${formatTime(target)}" else "快退 10 秒  ${formatTime(target)}", icon = seekIcon(delta > 0), progress = target.toFloat() / p.duration)
                            } else {
                                // 按住不放：只挪目标、显示要跳到哪，不每次都 seek（每次 seek 都要重新请求分片）；
                                // 松手时跳，万一收不到松手事件，1 秒没有新按键也跳
                                if (event.downTime != seekDownAt) {
                                    seekFrom = p.currentPosition
                                    seekLastAt = event.downTime
                                    seekDownAt = event.downTime
                                }
                                if (seekPending < 0) seekPending = p.currentPosition
                                // 往前最多到结尾前 15 秒：一下冲到结尾会直接换集，「下一集」卡片都来不及出现
                                val limit = if (forward) maxOf(p.currentPosition, p.duration - 15_000L) else p.duration
                                seekPending = SeekRamp.advance(seekPending, forward, event.eventTime - event.downTime, event.eventTime - seekLastAt, limit)
                                seekLastAt = event.eventTime
                                val moved = seekPending - seekFrom
                                flashHud("${if (moved >= 0) "快进" else "快退"} ${formatTime(abs(moved))}    ${formatTime(seekPending)} / ${formatTime(p.duration)}", 1_500, seekIcon(moved >= 0), seekPending.toFloat() / p.duration)
                                handler.removeCallbacks(commitSeek)
                                handler.postDelayed(commitSeek, 1_000)
                            }
                        }
                        return true
                    } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && !overlay && panel?.visibility == View.VISIBLE) {
                        toPanel()
                        return true
                    }
                }
                // Live: up / down change channel (the panel is one press of right away); on demand they jump to the panel.
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (isLive) {
                        if (event.repeatCount == 0) switchChannel(if (event.keyCode == KeyEvent.KEYCODE_DPAD_UP) 1 else -1)
                        return true
                    } else if (panel?.visibility == View.VISIBLE) {
                        toPanel()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** 按住快进 / 快退松手后，跳到攒下的位置。 */
    private fun finishHeldSeek() {
        handler.removeCallbacks(commitSeek)
        val target = seekPending
        if (target < 0) return
        seekPending = -1
        Log.d("TVP", "held seek: ${formatTime(seekFrom)} -> ${formatTime(target)}")
        player?.seekTo(target)
        val duration = player?.duration ?: 0L
        flashHud("跳到 ${formatTime(target)}", icon = seekIcon(target >= seekFrom), progress = if (duration > 0) target.toFloat() / duration else -1f)
    }

    // ---- 下一集提示 ------------------------------------------------------------------------------------------------

    /**
     * 「下一集」卡片直接挂在 PlayerView（本身是 FrameLayout）最上层，换布局时跟着它一起重建。不放进 overlayFrameLayout：
     * 那一层在控制器下面，手机上点按钮会被控制器挡住。
     */
    private fun setupNextCard() {
        val frame: FrameLayout = playerView
        val card = layoutInflater.inflate(R.layout.view_next_episode, frame, false)
        card.findViewById<View>(R.id.nextNow).setOnClickListener { playNextNow() }
        card.findViewById<View>(R.id.nextCancel).setOnClickListener { cancelNext() }
        // 电视上用确认键 / 返回键，不放按钮（放了也拿不到焦点）
        if (isTv) card.findViewById<View>(R.id.nextButtons).visibility = View.GONE
        // 放右上角：底部是电视的控制栏和手机的进度条；电视要留出安全区
        val (side, top) = if (isTv) dp(48) to dp(27) else dp(12) to dp(12)
        frame.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(side, top, side, top)
        })
        nextCard = card
        updateNextCard()
    }

    /**
     * 续播提示（腾讯视频、YouTube 的做法）：从观看记录接着播时，画面左下角一个胶囊「已从 12:34 继续播放 · 从头播放」，
     * 几秒后淡出。电视上按钮拿不到焦点，只写文字。
     */
    private fun setupResumeTip() {
        val tip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xCC161923.toInt())
                cornerRadius = dp(18).toFloat()
            }
            setPadding(dp(14), 0, dp(4), 0)
            addView(TextView(this@PlayActivity).apply {
                id = R.id.resumeText
                setTextColor(Color.WHITE)
                textSize = 13f
            })
            if (!isTv) addView(TextView(this@PlayActivity).apply {
                text = "从头播放"
                setTextColor(getColor(R.color.accent))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(10), 0, dp(10), 0)
                background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null)
                setOnClickListener { restartFromTop() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
            minimumHeight = dp(36)
        }
        // 左下角，在底栏和进度条上面；电视留出安全区
        val (side, bottom) = when {
            overlay -> dp(48) to dp(48) // 电视全屏：过扫描安全区
            isTv -> dp(12) to dp(12) // 电视详情页的小窗
            else -> dp(12) to dp(if (landscape) 76 else 40)
        }
        playerView.addView(tip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(side, 0, side, bottom)
        })
        resumeTip = tip
    }

    private fun showResumeTip(fromMs: Long) {
        val tip = resumeTip ?: return
        // 锁着屏点不得；控制栏亮着时会压在进度条上（续到哪进度条上也看得到）
        if (isLive || isInPictureInPictureMode || locked || controllerShown) return
        tip.findViewById<TextView>(R.id.resumeText).text = if (isTv) "已从上次的 ${formatTime(fromMs)} 继续播放" else "已从 ${formatTime(fromMs)} 继续播放"
        fadeTo(tip, true)
        handler.removeCallbacks(hideResumeTip)
        handler.postDelayed(hideResumeTip, RESUME_TIP_MS)
    }

    /** 续播提示里的「从头播放」：回到开头（设了跳过片头的照样跳过） */
    private fun restartFromTop() {
        handler.removeCallbacks(hideResumeTip)
        resumeTip?.let { fadeTo(it, false, gone = true) }
        player?.seekTo(0)
        introDone = false
        skipIntro(false)
        flashHud("从头播放")
    }

    /** 离这一集结束（设了跳过片尾就是片尾开始处）不到 10 秒时显示倒计时，其余时候藏起来。每秒由 ticker 调一次。 */
    private fun updateNextCard() {
        val card = nextCard ?: return
        val p = player
        val next = details[curSrc]?.lines?.getOrNull(curLine)?.episodes?.getOrNull(curEp + 1)
        var left = Long.MAX_VALUE
        if (p != null && p.duration > 0) {
            val outro = if (key.isEmpty()) 0 else Store.skip(key).second
            val end = if (outro > 0 && p.duration > (outro + 60) * 1000L) p.duration - outro * 1000L else p.duration
            left = ((end - p.currentPosition) / p.playbackParameters.speed.coerceAtLeast(0.1f)).toLong()
        }
        val show = p != null && next != null && !isLive && !replayMode && started && !nextCancelled && !sleepAfterEp && !locked &&
            !isInPictureInPictureMode && (overlay || !isTv) && !vfullShown && !(overlay && panel?.visibility == View.VISIBLE) &&
            p.duration >= NEXT_CARD_MIN_MS &&
            p.playbackState != Player.STATE_ENDED && seekPending < 0 && left in 1..NEXT_CARD_MS
        if (show) {
            card.findViewById<TextView>(R.id.nextTitle).text = "下一集：${next!!.name}"
            val secs = (left + 999) / 1000
            card.findViewById<TextView>(R.id.nextHint).text = if (isTv) "$secs 秒后自动播放 · 确认键立即播放 · 返回键取消" else "$secs 秒后自动播放"
        }
        if (show && card.visibility != View.VISIBLE) Log.d("TVP", "next card: ${next?.name}")
        card.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun playNextNow() {
        nextCard?.visibility = View.GONE
        val count = details[curSrc]?.lines?.getOrNull(curLine)?.episodes?.size ?: 0
        if (curEp + 1 >= count) return
        selectEpisode(curEp + 1)
        flashHud(epName(), 1_200)
    }

    private fun cancelNext() {
        nextCancelled = true
        nextCard?.visibility = View.GONE
        Log.d("TVP", "next card cancelled")
        flashHud("这一集放完后不自动播放下一集", 1_800)
    }

    // ---- 离线下载 ------------------------------------------------------------------------------------------------

    /** 把正在播的这一集加入下载。多码率的 m3u8 只下一种清晰度（不超过 1080P），不然每种都下一遍。 */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun downloadEpisode() {
        val url = resolvedUrl
        if (url == null || !started) {
            toast("还没开始播放，等能播了再下载")
            return
        }
        val id = "$key#$curEp"
        val info = Downloads.Info(title, epName(), pic)
        // Android 13 起要授权才能在通知栏看到下载进度（不授权也照样下）
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
        val params = androidx.media3.exoplayer.offline.DownloadHelper.getDefaultTrackSelectorParameters(this)
            .buildUpon().setMaxVideoSize(1920, 1080).build()
        val helper = androidx.media3.exoplayer.offline.DownloadHelper.forMediaItem(
            Downloads.mediaItem(url), params, AudioFallbackRenderersFactory(this), Downloads.playbackSource(this),
        )
        toast("正在准备下载…")
        helper.prepare(object : androidx.media3.exoplayer.offline.DownloadHelper.Callback {
            override fun onPrepared(h: androidx.media3.exoplayer.offline.DownloadHelper) {
                val request = h.getDownloadRequest(id, info.toBytes())
                h.release()
                androidx.media3.exoplayer.offline.DownloadService.sendAddDownload(this@PlayActivity, TvpDownloadService::class.java, request, false)
                // 之前因为前台服务超时暂停过的话，一起恢复
                androidx.media3.exoplayer.offline.DownloadService.sendResumeDownloads(this@PlayActivity, TvpDownloadService::class.java, false)
                toast(if (Store.downloadOnMetered()) "已加入下载，在「我的」→「下载」里查看" else "已加入下载（只在 Wi-Fi 下下载），在「我的」→「下载」里查看")
            }

            override fun onPrepareError(h: androidx.media3.exoplayer.offline.DownloadHelper, e: java.io.IOException) {
                h.release()
                toast("这一集没法下载：${e.message ?: "读不到视频信息"}")
            }
        })
    }

    // ---- 自动匹配帧率 ----------------------------------------------------------------------------------------------

    /** 电视上把刷新率切到片源帧率的整数倍（设置里开了才做）。离开播放页后系统自己恢复原来的刷新率。 */
    private fun matchFrameRate(fps: Float) {
        if (!isTv || !Store.matchFrameRate() || isFinishing) return
        val display = (if (Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay) ?: return
        val now = display.mode
        val modes = display.supportedModes.map { FrameRateMatch.Mode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
        val target = FrameRateMatch.pick(fps, FrameRateMatch.Mode(now.modeId, now.physicalWidth, now.physicalHeight, now.refreshRate), modes)
        Log.d("TVP", "frame rate ${"%.3f".format(Locale.US, fps)} fps on ${now.refreshRate} Hz: " +
            (target?.let { id -> "switch to ${modes.first { it.id == id }.refreshRate} Hz" } ?: "keep (${modes.size} modes)"))
        if (target == null) {
            // 现在的刷新率不合适、又没有合适的（比如上一集切到了 24Hz，这一集是 25 帧而电视没有 50Hz）：交还给系统默认
            if (!FrameRateMatch.fits(now.refreshRate, fps) && window.attributes.preferredDisplayModeId != 0) {
                window.attributes = window.attributes.apply { preferredDisplayModeId = 0 }
            }
            return
        }
        if (window.attributes.preferredDisplayModeId == target) return
        window.attributes = window.attributes.apply { preferredDisplayModeId = target }
    }

    // ---- 字幕大小和时间 ----------------------------------------------------------------------------------------------

    /** 字幕大小按设置放大缩小，并叠加系统无障碍里的字幕缩放（PlayerView 默认就是这么算的）。 */
    private fun applySubtitleStyle() {
        val view = playerView.subtitleView ?: return
        val size = Store.subtitleSize()
        val captioning = getSystemService(android.view.accessibility.CaptioningManager::class.java)
        val user = if (captioning?.isEnabled == true) captioning.fontScale else 1f
        view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * SUB_SCALES[size] * user)
        // 不是「标准」时，字幕文件自带的字号也不用，按这里选的来
        view.setApplyEmbeddedFontSizes(size == 1)
    }

    private fun subOffsetText() = when {
        subOffsetMs == 0L -> "不调整"
        subOffsetMs > 0 -> "延后 %.1f 秒".format(Locale.US, subOffsetMs / 1000f)
        else -> "提前 %.1f 秒".format(Locale.US, -subOffsetMs / 1000f)
    }

    /** 按现在的偏移重写一份导入的字幕并重新加载（停手 0.8 秒后才做，按住 + / − 连调时只重载一次）。 */
    private fun shiftSubtitle() {
        val base = extSubtitleBase ?: return
        val offset = subOffsetMs
        val mime = extSubtitleMime
        val g = gen
        lifecycleScope.launch {
            val shifted = withContext(Dispatchers.IO) { runCatching { Subtitles.shiftedCopy(base, mime, offset) }.getOrNull() }
            // 这期间又导入了别的字幕或又调了时间：以新的为准
            if (shifted == null || base != extSubtitleBase || offset != subOffsetMs) return@launch
            extSubtitle = shifted
            // 这期间换了集或线路：新的那条还没起播就不重载（它起播时用的就是新字幕），已经在播才重载
            if (g == gen || started) reloadStream()
            flashHud("字幕${subOffsetText()}", 1_200)
        }
    }

    // ---- gestures: swipe to seek / brightness / volume, double tap to pause, long press for 2x ------------------

    private fun setupGestures() {
        val view = playerView
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val label = GestureHud(this)
        val wrap = FrameLayout.LayoutParams.WRAP_CONTENT
        view.overlayFrameLayout?.addView(label, FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER))
        hud = label
        val speedTip = SpeedTip(this)
        view.overlayFrameLayout?.addView(speedTip, FrameLayout.LayoutParams(wrap, wrap, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(20) })
        // 双击两侧快进 / 快退：贴边的半个椭圆，宽度显示时按画面宽度定
        val ripples = listOf(TapSeekRipple(this, false), TapSeekRipple(this, true))
        ripples.forEachIndexed { i, r ->
            view.overlayFrameLayout?.addView(r, FrameLayout.LayoutParams(wrap, FrameLayout.LayoutParams.MATCH_PARENT, if (i == 0) Gravity.START else Gravity.END))
        }
        val hideRipples = Runnable { ripples.forEach { it.hide() } }
        // 连着点的那一串：哪一侧、攒了几秒、最后一下什么时候点的
        var tapSide = 0
        var tapTotalMs = 0L // 这一串实际跳了多少（到了开头 / 片尾前 15 秒就跳不满 10 秒）
        var tapAt = 0L
        var tapUsedDown = -1L // 被连点快进用掉的那次按下：它之后的「单击确认」不再开关控制栏
        var doubleTapDown = -1L // 最近一次双击的第二下：按住不放也不进倍速

        // 双击落在哪一侧：-1 左（快退）、1 右（快进）、0 中间或这时不能拖进度（直播、竖屏全屏的一页页切集）
        fun sideOf(e: MotionEvent): Int {
            val p = player ?: return 0
            if (!canSeek() || p.duration <= 0 || (portraitVideo && vfullShown)) return 0
            return when {
                e.x < view.width * TAP_SEEK_EDGE -> -1
                e.x > view.width * (1 - TAP_SEEK_EDGE) -> 1
                else -> 0
            }
        }

        fun tapSeek(side: Int) {
            val p = player ?: return
            val now = SystemClock.uptimeMillis()
            if (side != tapSide || now - tapAt > TAP_SEEK_WINDOW_MS) tapTotalMs = 0L
            tapSide = side
            tapAt = now
            // 往前最多到结尾前 15 秒（和遥控器按住快进一样）：一下跳到结尾会直接换集，后面几下还会跳进下一集
            val cur = p.currentPosition
            val target = if (side > 0) minOf(cur + TAP_SEEK_STEP_MS, maxOf(cur, p.duration - 15_000L)) else maxOf(0L, cur - TAP_SEEK_STEP_MS)
            if (target != cur) p.seekTo(target)
            tapTotalMs += abs(target - cur)
            view.hideController()
            label.hide()
            val r = ripples[if (side < 0) 0 else 1]
            ripples[if (side < 0) 1 else 0].hide()
            r.layoutParams = r.layoutParams.also { it.width = (view.width * 0.36f).toInt() }
            val secs = ((tapTotalMs + 500) / 1000).toInt()
            r.show(
                when {
                    secs > 0 -> "${if (side < 0) "快退" else "快进"} $secs 秒\n${formatTime(target)}"
                    side < 0 -> "已到开头"
                    else -> "快到结尾了"
                },
            )
            handler.removeCallbacks(hideRipples)
            handler.postDelayed(hideRipples, TAP_SEEK_WINDOW_MS)
        }

        fun inTapSeries() = SystemClock.uptimeMillis() - tapAt < TAP_SEEK_WINDOW_MS

        // 0 还没定，1 拖进度，2 亮度，3 音量，4 普通切集，5 已经切了（或这一下不算），8 整页跟手切集
        var mode = 0
        var downRawY = 0f
        var startPos = 0L
        var seekTarget = 0L
        var startBrightness = 0f
        var startVolume = 0

        val detector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true

                // 双击两侧之后，同一侧再单点也接着攒（YouTube 的做法），不用每次都双击
                // 点到中间或另一侧就结束这一串，那一下照常算（另一侧要双击才开始往那边跳）
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    if (inTapSeries()) {
                        if (sideOf(e) == tapSide) {
                            tapSeek(tapSide)
                            tapUsedDown = e.downTime
                        } else {
                            tapAt = 0L
                        }
                    }
                    return false
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (e.downTime == tapUsedDown) return true // 这一下已经拿去快进了
                    if (controllerShown) view.hideController() else view.showController()
                    return true
                }

                // onDoubleTap 拿到的是第一下的按下，长按回调拿到的是第二下的，所以第二下要在这里记
                override fun onDoubleTapEvent(e: MotionEvent): Boolean {
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) doubleTapDown = e.downTime
                    return false
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val side = sideOf(e)
                    if (side != 0) {
                        tapSeek(side)
                    } else if (!inTapSeries()) {
                        player?.let { if (it.isPlaying) it.pause() else it.play() }
                    }
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    if (isLive && !replayMode) return
                    if (inTapSeries() || e.downTime == doubleTapDown) return
                    speedBoost = true
                    val boost = maxOf(2f, Store.playSpeed()) // at 3× already, holding must not slow it down
                    player?.setPlaybackSpeed(boost)
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    view.hideController()
                    // 竖屏全屏顶上有返回、标题、选集那一排，放到它下面
                    (speedTip.layoutParams as FrameLayout.LayoutParams).topMargin = dp(if (portraitVideo && vfullShown) 76 else 20)
                    speedTip.requestLayout()
                    speedTip.show("${boost}×".replace(".0×", "×") + " 倍速播放中")
                }

                override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                    if (e1 == null) return false
                    val p = player ?: return false
                    if (mode == 0) {
                        if (abs(e2.x - e1.x) > abs(e2.y - e1.y)) {
                            if (!canSeek() || p.duration <= 0) return false
                            mode = 1
                            startPos = p.currentPosition
                        } else if (portraitVideo && vfullShown && e1.x in view.width * 0.2f..view.width * 0.8f) {
                            // 竖屏全屏的中间是一页页的集：上滑下一集、下滑上一集，整页跟着手指（下面的触摸监听接手）。
                            // 两边照旧是亮度（左）和音量（右）；退出全屏用右下角的箭头
                            // 上一次滑动的动画还没走完：这一下不算，免得和它一起连跳两集
                            mode = if (feed != null) 5 else if (beginFeed(if (e2.y > e1.y) -1 else 1)) 8 else 4
                            if (mode == 8) feedDrag(e1.y - e2.y)
                        } else if (e1.x < view.width / 2) {
                            mode = 2
                            startBrightness = currentBrightness()
                        } else {
                            mode = 3
                            startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                        }
                    }
                    if (mode == 4) {
                        // 接管不了整页时（比如缺卡片布局），普通的上下滑照样切集
                        val pulled = e2.y - e1.y
                        if (abs(pulled) > dp(72)) {
                            mode = 5
                            val step = if (pulled < 0) 1 else -1
                            handler.post { stepEpisode(step) }
                        }
                        return true
                    }
                    if (mode == 5 || mode == 8) return true
                    view.hideController()
                    val dx = e2.x - e1.x
                    val up = e1.y - e2.y
                    handler.removeCallbacks(hideHud) // 别让之前的提示到点把正在拖的这个收掉
                    when (mode) {
                        1 -> {
                            seekTarget = (startPos + (dx / view.width * 90_000).toLong()).coerceIn(0, p.duration)
                            val moved = seekTarget - startPos
                            label.show(
                                "${if (moved >= 0) "快进" else "快退"} ${formatTime(abs(moved))}    ${formatTime(seekTarget)} / ${formatTime(p.duration)}",
                                seekIcon(moved >= 0),
                                seekTarget.toFloat() / p.duration,
                            )
                        }
                        2 -> {
                            val b = (startBrightness + up / view.height * 1.2f).coerceIn(0.02f, 1f)
                            window.attributes = window.attributes.also { it.screenBrightness = b }
                            label.show("亮度 ${(b * 100).roundToInt()}%", R.drawable.ic_hud_brightness, b)
                        }
                        3 -> {
                            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            val v = (startVolume + up / view.height * max * 1.2f).roundToInt().coerceIn(0, max)
                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                            label.show(
                                "音量 ${v * 100 / maxOf(1, max)}%",
                                if (v == 0) R.drawable.ic_hud_volume_off else R.drawable.ic_hud_volume,
                                v.toFloat() / maxOf(1, max),
                            )
                        }
                    }
                    return true
                }
            },
        )

        view.setOnTouchListener { v, event ->
            // 锁屏：手势全部不响应，点一下只露出锁键
            if (locked) {
                if (event.actionMasked == MotionEvent.ACTION_UP) showLockBriefly()
                return@setOnTouchListener true
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN) downRawY = event.rawY
            // 手指的速度用屏幕坐标算：画面在手指下面跟着动，用它自己的坐标会算错
            if (event.actionMasked == MotionEvent.ACTION_DOWN) velocity.clear()
            MotionEvent.obtain(event).also {
                it.setLocation(event.rawX, event.rawY)
                velocity.addMovement(it)
                it.recycle()
            }
            if (mode == 8) {
                // 整页跟着手指走，松手时要么切过去、要么弹回
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> feedDrag(downRawY - event.rawY)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        velocity.computeCurrentVelocity(1000)
                        endFeed(event.actionMasked == MotionEvent.ACTION_UP, velocity.yVelocity)
                        mode = 0
                    }
                }
                return@setOnTouchListener true
            }
            detector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (mode == 1 && event.actionMasked == MotionEvent.ACTION_UP) player?.seekTo(seekTarget)
                if (speedBoost) {
                    player?.setPlaybackSpeed(Store.playSpeed())
                    speedBoost = false
                    speedTip.hide()
                }
                // 只收手势自己的提示；别的提示（比如刚切的集名）按自己的时间走
                if (mode in 1..3) label.hide()
                mode = 0
                // PlayerView.performClick toggles the controller by itself, and the confirmed tap above toggles it again:
                // on a phone that cancelled out and a tap changed nothing. On TV the click opens the full screen.
                if (event.actionMasked == MotionEvent.ACTION_UP && isTv) v.performClick()
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

    private fun seekIcon(forward: Boolean) = if (forward) R.drawable.ic_hud_forward else R.drawable.ic_hud_rewind

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    // ---- lifecycle -----------------------------------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        leaving = false
        stopKeepAlive()
        handler.postDelayed(saver, SAVE_MS)
        updateFavorite()
    }

    override fun onStart() {
        super.onStart()
        if (videoOff) {
            videoOff = false
            setVideoEnabled(true)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(saver)
        saveProgress()
        val inPip = pipRequested || isInPictureInPictureMode
        val audio = !isTv && !inPip && !isFinishing && !isChangingConfigurations && Store.leaveAction() == "audio" && player?.isPlaying == true
        if (audio) {
            // A dialog in front (e.g. the notification permission prompt) keeps playing without the notification;
            // only really leaving (Home, recents, screen off) needs the foreground service.
            if (leaving || !getSystemService(PowerManager::class.java).isInteractive) {
                KeepAliveService.onStop = {
                    runOnUiThread {
                        player?.pause()
                        finish()
                    }
                }
                KeepAliveService.start(this, title)
                keepAlive = true
            }
        } else if (!inPip) {
            player?.pause()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isInPictureInPictureMode && getSystemService(PowerManager::class.java).isInteractive) {
            // The floating window was closed (a screen turning off also stops the activity; that is not a close).
            player?.pause()
            finishAndRemoveTask()
        } else if (player?.isPlaying == true && !isFinishing) {
            // Still playing with the screen gone: only the sound; drop the picture to save data and decoding.
            videoOff = true
            setVideoEnabled(false)
        }
    }

    override fun onDestroy() {
        endStall("退出时还在缓冲")
        logSummary()
        abandonPull()
        stopKeepAlive()
        playJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        preloadManager?.release()
        preloadManager = null
        prefetch?.release()
        prefetchPool.shutdownNow()
        velocity.recycle()
        super.onDestroy()
    }
}
