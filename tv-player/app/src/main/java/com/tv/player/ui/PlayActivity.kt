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
import android.graphics.drawable.ColorDrawable
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
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import androidx.media3.exoplayer.source.preload.PreloadException
import androidx.media3.exoplayer.source.preload.PreloadManagerListener
import androidx.media3.exoplayer.source.preload.TargetPreloadStatusControl
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
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
import com.tv.player.core.Library
import com.tv.player.core.Quark
import com.tv.player.core.QuarkWeb
import com.tv.player.core.Resolve
import com.tv.player.core.CachePolicy
import com.tv.player.core.FallbackLatch
import com.tv.player.core.PrefetchPolicy
import com.tv.player.core.PrefetchScheduler
import com.tv.player.core.PlayCache
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
        private const val SAVE_MS = 10_000L
        private const val MAX_SKIP = 300
        private const val EP_GROUP = 30
        private const val RESCUE_MS = 4_000L
        private const val PRELOAD_AFTER_MS = 2_500L // how long an episode plays before its neighbours are loaded ahead
        private const val PRELOAD_MS = 5_000L // how much of the next episode is loaded ahead
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

    // Stalls in this viewing: the more there were, the longer playback gathers buffer before resuming (see StallPolicy).
    private val stallPolicy = StallPolicy()

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
    private val preloadNext = Runnable { preloadNextEpisode() }
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
    private var hud: TextView? = null
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

    /**
     * Phones, tall pictures (short dramas): the picture fills the whole screen, like a vertical video in Bilibili. A pull
     * down on the small picture gets there, a pull up (or back) leaves. [portraitVideo] says the playing picture is tall.
     */
    private var portraitFull = false
    /** From the controller's own visibility callback: isControllerFullyVisible keeps saying true once the controller has timed out. */
    private var controllerShown = false
    private var portraitVideo = false
    private var vfullTipShown = false
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

        val httpFactory = DefaultHttpDataSource.Factory()
            // As a default property (not setUserAgent, which is applied last and would replace a per-request one).
            .setDefaultRequestProperties(mapOf("User-Agent" to Http.UA))
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(10_000)
        // One retry instead of the default three, so a dead line hands over to the next one quickly.
        val failFast = object : DefaultLoadErrorHandlingPolicy() {
            override fun getMinimumLoadableRetryCount(dataType: Int) = 1
        }
        // Start after 1 s of video instead of the default 2.5 s; the rest keeps buffering while it plays.
        val loadControl = StallAwareLoadControl(
            DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 1_000, 2_000).build(),
            stallPolicy,
        )
        // Drive addresses (web login) need the session cookies on every request to the drive's hosts.
        val network = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, httpFactory)) { spec ->
            val extra = spec.uri.host?.let(QuarkWeb::headersFor)
            if (extra == null) spec else spec.withRequestHeaders(spec.httpRequestHeaders + extra)
        }
        // On-demand video is cached on disk as it plays (and as the next episode is loaded ahead); live is not, and neither
        // are a drive's files: their addresses expire and need the login.
        // A drive's files (either kind of login) are left alone as a whole: one film would fill the cache on its own.
        val fromDrive = !isLive && items.any { it.sourceKey == Quark.SOURCE_KEY || it.sourceKey == QuarkWeb.SOURCE_KEY }
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
            // A phone whose preferred video decoder fails to start falls back to another one instead of failing the line.
            .setRenderersFactory(DefaultRenderersFactory(this).setEnableDecoderFallback(true))
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
                it.playWhenReady = true
                it.setPlaybackSpeed(if (isLive) 1f else Store.playSpeed())
                it.setSeekParameters(SeekParameters.CLOSEST_SYNC) // swipe and arrow seeks land on the nearest keyframe: much quicker
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
            if (vfullShown) {
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
        })
        playerView.player = controlPlayer ?: player
        // The controller's own gear opens the same panel as the app's: one place for speed, picture, subtitles and the rest.
        playerView.findViewById<View?>(androidx.media3.ui.R.id.exo_settings)?.setOnClickListener { showPlaySettings() }
        playerView.resizeMode = resizeModeOf(Store.resizeMode())
        findViewById<TextView?>(R.id.title)?.text = title
        findViewById<View?>(R.id.back)?.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        // The tall full screen's own buttons: that layer is part of this layout and fades in as the picture grows.
        findViewById<View?>(R.id.vBack)?.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
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
        if (vfullShown) applyPortraitFull(animate = false) else {
            updateOverlayTitle()
            syncPullArrow()
        }
        setupGestures()
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

    // ---- pulling the small picture down into the full screen (follows the finger, like Bilibili) -------------------------

    /** How the picture has to be scaled and moved to become the full-screen picture, and how far a finger must go for that. */
    private class Pull(val scale: Float, val dy: Float, val range: Float)

    private var pull: Pull? = null
    private var pullT = 0f
    private var pullAnimator: ValueAnimator? = null

    /** Takes hold of the small picture for a pull; false when it cannot be worked out (then the drag is a volume swipe). */
    private fun beginPull(): Boolean {
        if (pull != null) return false // the last one is still settling
        val root = findViewById<ConstraintLayout?>(R.id.root) ?: return false
        val size = player?.videoSize ?: return false
        if (size.width <= 0 || size.height <= 0 || playerView.height <= 0 || root.height <= 0) return false
        val aspect = size.width * size.pixelWidthHeightRatio / size.height
        val boxH = playerView.height.toFloat()
        val shownH = minOf(boxH, playerView.width / aspect) // the picture as it is now: fitted into its 16:9 box
        val fullW = minOf(root.width.toFloat(), root.height * aspect) // and as it will be: fitted into the whole screen
        val scale = (fullW / aspect) / shownH
        val dy = root.height / 2f - (playerView.top + boxH / 2f)
        pull = Pull(scale, dy, root.height * 0.3f)
        pullT = 0f
        playerView.hideController()
        playerView.translationZ = dp(8).toFloat() // above the lists, which are drawn after it
        root.setBackgroundColor(Color.BLACK)
        return true
    }

    private fun setPull(t: Float) {
        val g = pull ?: return
        pullT = t
        val s = 1f + (g.scale - 1f) * t
        playerView.scaleX = s
        playerView.scaleY = s
        playerView.translationY = g.dy * t
        findViewById<View?>(R.id.topbar)?.alpha = 1f - t
        findViewById<View?>(R.id.content)?.alpha = 1f - t
        findViewById<View?>(R.id.pullArrow)?.apply {
            translationY = t * dp(56)
            alpha = 0.85f * (1f - t)
        }
    }

    /** Letting go: past a third of the way it goes on to the full screen, short of that it springs back. */
    private fun endPull(up: Boolean) {
        if (pull == null) return
        val commit = up && pullT >= 0.3f
        val from = pullT
        val to = if (commit) 1f else 0f
        pullAnimator?.cancel()
        var cancelled = false
        pullAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = 160L + (180 * abs(to - from)).toLong()
            interpolator = DecelerateInterpolator()
            addUpdateListener { setPull(it.animatedValue as Float) }
            doOnCancel { cancelled = true }
            doOnEnd { if (!cancelled) finishPull(commit) }
            start()
        }
    }

    /** The page is being rebuilt or left in the middle of a pull: nothing is finished on the old views, the pull just ends. */
    private fun abandonPull() {
        pullAnimator?.removeAllListeners()
        pullAnimator?.cancel()
        pullAnimator = null
        pull = null
    }

    /** The pull is over: the picture is back at rest, and if it was taken all the way the page really is the full screen. */
    private fun finishPull(commit: Boolean) {
        playerView.scaleX = 1f
        playerView.scaleY = 1f
        playerView.translationY = 0f
        playerView.translationZ = 0f
        findViewById<View?>(R.id.topbar)?.alpha = 1f
        findViewById<View?>(R.id.content)?.alpha = 1f
        findViewById<View?>(R.id.pullArrow)?.apply {
            translationY = 0f
            alpha = 0.85f
        }
        pull = null
        pullAnimator = null
        if (commit && portraitVideo && canPortraitFull) {
            portraitFull = true
            applyPortraitFull(animate = false)
            applySystemBars()
            findViewById<View?>(R.id.vOverlay)?.apply {
                alpha = 0f
                animate().alpha(1f).setDuration(180).start()
            }
            playerView.showController()
            val episodes = details[curSrc]?.lines?.getOrNull(curLine)?.episodes?.size ?: 0
            if (episodes > 1 && Store.swipeTips() < 3) {
                Store.bumpSwipeTips()
                flashHud("上滑下一集，下滑上一集", 2_500)
            }
        } else {
            findViewById<ConstraintLayout?>(R.id.root)?.setBackgroundColor(getColor(R.color.bg))
        }
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
    }

    /** The hairline at the bottom of the tall full screen shows how far along the episode is while the controls are away. */
    private fun syncThinProgress() {
        val bar = findViewById<ProgressBar?>(R.id.vProgress) ?: return
        bar.visibility = if (controllerShown) View.INVISIBLE else View.VISIBLE
        val p = player ?: return
        if (p.duration > 0) bar.progress = (p.currentPosition * 1000 / p.duration).toInt().coerceIn(0, 1000)
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
        val episodes = details[curSrc]?.lines?.getOrNull(curLine)?.episodes?.size ?: 0
        if (full && episodes > 1 && Store.swipeTips() < 3) {
            Store.bumpSwipeTips()
            flashHud("上滑下一集，下滑上一集", 2_500)
        }
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
        progressText?.text = if (!canSeek() || p.duration <= 0) "" else "${formatTime(p.currentPosition)} / ${formatTime(p.duration)}"
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
            }
            when (state) {
                Player.STATE_READY -> {
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
                    setInfo(playingText())
                }
                Player.STATE_ENDED -> if (replayMode) setInfo("回看已播完，可以换一段，或点「回到直播」") else if (!isLive) {
                    if (!stalledThisEpisode) stallPolicy.onSmoothEpisode() // played through without a stall: ease off
                    if (sleepAfterEp) {
                        sleepNow("这一集播完了，已按睡眠定时停止")
                        return
                    }
                    val count = details[curSrc]?.lines?.getOrNull(curLine)?.episodes?.size ?: 0
                    if (curEp + 1 < count) selectEpisode(curEp + 1)
                }
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.height <= 0) return
            portraitVideo = videoSize.width * videoSize.pixelWidthHeightRatio < videoSize.height
            syncPullArrow()
            if (!portraitVideo && portraitFull) setPortraitFull(false)
            if (portraitVideo && canPortraitFull && !portraitFull && !vfullTipShown && Store.vfullTips() < 3) {
                vfullTipShown = true
                Store.bumpVfullTips()
                flashHud("下拉画面，竖屏全屏", 2_500)
            }
            resolution = qualityLabel(videoSize.height)
            if (!replayMode) lineQuality[lineKey()] = resolution // a replayed programme's picture is not a line's quality
            if (player?.playbackState == Player.STATE_READY) setInfo(playingText())
            refreshLists()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateKeepScreen()
            updatePlayPause()
            // Paused on TV: keep the bar up so the picture is not left frozen with nothing on screen.
            if (overlay && panel?.visibility == View.VISIBLE) bumpPanel()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = updateKeepScreen()

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            if (!isLive && !speedBoost) Store.setPlaySpeed(playbackParameters.speed)
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.d("TVP", "player error ${error.errorCodeName} on ${current?.label}")
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
        allFailed = false
        updateKeepScreen()
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
        attempt()
    }

    /** Picks the next untried line/source and starts it. */
    private fun attempt() {
        playJob?.cancel()
        // The episode that is starting needs the bandwidth more than the ones after it: what is still being resolved is
        // dropped. What the manager has loaded is kept (an invalidate here would empty the very source about to be played).
        preloadJob?.cancel()
        handler.removeCallbacks(preloadNext)
        stalledThisEpisode = false
        handler.removeCallbacks(stall)
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
        var panel: androidx.appcompat.app.AlertDialog? = null
        val chips = ChipAdapter(fill = true) { index ->
            panel?.dismiss()
            if (index != curEp) selectEpisode(index)
        }
        chips.submit(episodes.map { Regex("^第0*(\\d+)[集期话]$").find(it.name)?.groupValues?.get(1) ?: it.name }, curEp)
        val grid = BoundedRecyclerView(this, panelListHeight()).apply {
            layoutManager = GridLayoutManager(this@PlayActivity, 5)
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
        panel = dialog
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
        epLabel?.text = "选集（共 ${episodes.size} 集）"
        updateOverlayTitle()
        episodesView?.scrollToPosition((curEp - epOffset).coerceAtLeast(0))
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
        val entry = Library.Entry(key, title, pic, items, curEp, epName(), if (nearEnd) 0 else pos)
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
                    } else if (curEp + 1 < count) {
                        flashHud("已跳过片尾，播放下一集")
                        selectEpisode(curEp + 1)
                    }
                }
            }
            if (overlay && panel?.visibility == View.VISIBLE) updateProgress()
            if (vfullShown) syncThinProgress()
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
        view.findViewById<View>(R.id.importSub).setOnClickListener {
            try {
                pickSubtitle.launch(arrayOf("*/*"))
            } catch (e: ActivityNotFoundException) {
                toast("这台设备没有文件选择器，没法导入字幕")
            }
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

        if (isTv) {
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
    private fun showSidePanel(title: String, content: View, scrollOnly: Boolean = false) {
        val dialog = android.app.Dialog(this, R.style.Theme_TvPlayer_SidePanel)
        // With buttons inside, the scroll container must not take focus (it would draw a highlight); with plain text
        // ([scrollOnly]) it has to, or the remote could not scroll it.
        if (!scrollOnly) (content as? ViewGroup)?.apply {
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(27), dp(24), dp(27)) // TV overscan safe area on the top, right and bottom
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
            setLayout(dp(420), ViewGroup.LayoutParams.MATCH_PARENT)
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
        dialog.show()
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
        val gone = if (inPip) View.GONE else View.VISIBLE
        for (id in listOf(R.id.topbar, R.id.content, R.id.back, R.id.title, R.id.cast, R.id.playSettings)) findViewById<View?>(id)?.visibility = gone
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
            extSubtitleMime = mime
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

    private val hideHud = Runnable { hud?.visibility = View.GONE }

    private fun flashHud(text: String, ms: Long = 900) {
        hud?.apply {
            this.text = text
            visibility = View.VISIBLE
        }
        handler.removeCallbacks(hideHud)
        handler.postDelayed(hideHud, ms)
    }

    /** With the picture focused and the controller hidden: left/right seek 10s, up/down jump to the side panel. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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
                        flashHud(if (delta > 0) "快进 30 秒  ${formatTime(target)}" else "快退 30 秒  ${formatTime(target)}")
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
                            val delta = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 10_000L else -10_000L
                            val target = (p.currentPosition + delta).coerceIn(0L, maxOf(0L, p.duration))
                            p.seekTo(target)
                            playerView.hideController()
                            flashHud(if (delta > 0) "快进 10 秒  ${formatTime(target)}" else "快退 10 秒  ${formatTime(target)}")
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

        var mode = 0 // 0 undecided, 1 seek, 2 brightness, 3 volume, 4 swiping to another episode, 5 swiped, 6 pulling the picture down
        var downRawY = 0f
        var startPos = 0L
        var seekTarget = 0L
        var startBrightness = 0f
        var startVolume = 0

        val detector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (controllerShown) view.hideController() else view.showController()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    player?.let { if (it.isPlaying) it.pause() else it.play() }
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    if (isLive && !replayMode) return
                    speedBoost = true
                    val boost = maxOf(2f, Store.playSpeed()) // at 3× already, holding must not slow it down
                    player?.setPlaybackSpeed(boost)
                    label.text = "${boost}×".replace(".0×", "×") + " 倍速"
                    label.visibility = View.VISIBLE
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
                            // In the tall full screen, the middle is a feed of episodes; the sides keep brightness (left) and
                            // volume (right).
                            mode = 4
                        } else if (portraitVideo && canPortraitFull && !vfullShown && e1.x in view.width * 0.28f..view.width * 0.72f && beginPull()) {
                            // A tall picture in the small window: a pull down takes hold of it. From here the touch listener
                            // below follows the finger (with raw coordinates: the picture moves under it) until it lets go.
                            mode = 6
                        } else if (e1.x < view.width / 2) {
                            mode = 2
                            startBrightness = currentBrightness()
                        } else {
                            mode = 3
                            startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                        }
                    }
                    if (mode == 4) {
                        // In the full screen it is a feed of episodes: swipe up for the next one, down for the previous.
                        val pulled = e2.y - e1.y
                        if (abs(pulled) > dp(72)) {
                            mode = 5
                            val step = if (pulled < 0) 1 else -1
                            handler.post { stepEpisode(step) }
                        }
                        return true
                    }
                    if (mode == 5 || mode == 6) return true
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
            if (event.actionMasked == MotionEvent.ACTION_DOWN) downRawY = event.rawY
            if (mode == 6) {
                // The picture is being pulled: it follows the finger, grows with it, and on letting go either goes on to the
                // full screen or springs back.
                when (event.actionMasked) {
                    MotionEvent.ACTION_MOVE -> pull?.let { setPull(((event.rawY - downRawY) / it.range).coerceIn(0f, 1f)) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        mode = 0
                        endPull(event.actionMasked == MotionEvent.ACTION_UP)
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
                }
                mode = 0
                label.visibility = View.GONE
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
        super.onDestroy()
    }
}
