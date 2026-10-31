package com.tv.player.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.tv.player.R
import com.tv.player.core.Downloads

/**
 * 只有画面的精简播放页，两种用法：
 * - 手机投过来的视频（DLNA，见 CastSession）：手机那边的暂停、拖动、停止都作用在这里；
 * - 离线模式（offline）：播放下载好的一集（见 Downloads），只从下载目录读，不需要网络。
 * 电视上左右键快进快退 10 秒、确认键暂停、返回键结束；手机和平板上用播放器自带的控制条。
 */
class CastActivity : BaseActivity() {
    companion object {
        fun intent(context: Context, url: String, title: String) = Intent(context, CastActivity::class.java)
            .putExtra("url", url)
            .putExtra("title", title)

        /** 播放下载好的 [downloadId]。 */
        fun offline(context: Context, downloadId: String, title: String) = Intent(context, CastActivity::class.java)
            .putExtra("offline", downloadId)
            .putExtra("title", title)
    }

    private var offline = false

    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var hud: TextView
    private var url = ""
    private var failed = false
    private val handler = Handler(Looper.getMainLooper())
    private val hideHud = Runnable { hud.visibility = View.GONE }
    private val reporter = object : Runnable {
        override fun run() {
            report()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cast)
        view = findViewById(R.id.player)
        hud = findViewById(R.id.hud)
        offline = intent.hasExtra("offline")
        // 两种用法共用一个数据源：先从下载目录读，没有的走网络。这样投屏页开着时再点离线播放（或反过来）都能直接放
        player = ExoPlayer.Builder(this)
            .setRenderersFactory(AudioFallbackRenderersFactory(this))
            .setMediaSourceFactory(DefaultMediaSourceFactory(Downloads.playbackSource(this)))
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { p ->
                p.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        failed = true
                        flash("投过来的视频放不了（${error.errorCodeName}），可以在手机上换一个清晰度再投", 6_000)
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                })
            }
        view.player = player
        view.useController = !isTv()
        // 手机上横过来看（转屏不重建页面，见清单里的 configChanges）
        if (!isTv()) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        if (offline) {
            playDownload(intent.getStringExtra("offline").orEmpty(), intent.getStringExtra("title").orEmpty())
            return
        }
        CastSession.attach(this)
        load(intent.getStringExtra("url").orEmpty(), intent.getStringExtra("title").orEmpty())
        handler.post(reporter)
    }

    /** 下载请求里存着地址和要哪些码流，照着它从下载目录播。 */
    private fun playDownload(id: String, title: String) {
        val download = runCatching { Downloads.manager(this).downloadIndex.getDownload(id) }.getOrNull()
        if (download == null) {
            flash("这一集的下载找不到了", 4_000)
            return
        }
        player?.apply {
            setMediaItem(download.request.toMediaItem())
            prepare()
            play()
        }
        flash(title, 3_000)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val title = intent.getStringExtra("title").orEmpty()
        if (intent.hasExtra("offline")) {
            // 投屏中改成离线播放：不再替手机报状态
            if (!offline) {
                offline = true
                handler.removeCallbacks(reporter)
                CastSession.detach(this)
            }
            playDownload(intent.getStringExtra("offline").orEmpty(), title)
            return
        }
        if (offline) {
            // 离线播放时手机投过来了：改成投屏
            offline = false
            CastSession.attach(this)
            handler.post(reporter)
        }
        load(intent.getStringExtra("url").orEmpty(), title)
    }

    fun load(url: String, title: String) {
        val p = player ?: return
        if (url.isEmpty()) return
        this.url = url
        failed = false
        val item = MediaItem.Builder().setUri(url).apply {
            if (url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        p.setMediaItem(item)
        p.prepare()
        p.play()
        flash("手机投屏：$title", 3_000)
    }

    fun resume() {
        player?.play()
    }

    fun pause() {
        player?.pause()
    }

    fun seekTo(ms: Long) {
        player?.seekTo(ms)
        flash(formatTime(ms))
    }

    private fun report() {
        val p = player ?: return
        val state = when {
            failed -> "STOPPED"
            p.playbackState == Player.STATE_ENDED || p.playbackState == Player.STATE_IDLE -> "STOPPED"
            !p.playWhenReady -> "PAUSED_PLAYBACK"
            p.playbackState == Player.STATE_BUFFERING && p.currentPosition == 0L -> "TRANSITIONING"
            else -> "PLAYING"
        }
        val duration = if (p.duration == C.TIME_UNSET) 0 else p.duration
        CastSession.report(this, state, p.currentPosition, duration, url)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player
        if (p != null && !view.useController && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    if (p.isCurrentMediaItemSeekable) {
                        val forward = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                        val target = (p.currentPosition + if (forward) 10_000 else -10_000).coerceIn(0, maxOf(0, p.duration))
                        seekTo(target)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (event.repeatCount == 0) {
                        if (p.playWhenReady) p.pause() else p.play()
                        flash(if (p.playWhenReady) "播放" else "已暂停")
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun flash(text: String, ms: Long = 1_200) {
        hud.text = text
        hud.visibility = View.VISIBLE
        handler.removeCallbacks(hideHud)
        handler.postDelayed(hideHud, ms)
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    override fun onStop() {
        super.onStop()
        // 切出去（按了主页键）就暂停，回来手机上点播放或按确认键继续
        player?.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (!offline) CastSession.detach(this)
        player?.release()
        player = null
        super.onDestroy()
    }
}
