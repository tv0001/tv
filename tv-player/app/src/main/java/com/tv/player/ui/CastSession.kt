package com.tv.player.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.tv.player.core.DlnaRenderer
import com.tv.player.core.Store
import java.lang.ref.WeakReference

/**
 * 投屏接收的界面这一侧：DlnaRenderer 收到手机的命令后交给这里，这里切到主线程，打开或控制 CastActivity；
 * CastActivity 每半秒把播放状态报回来，手机查询进度时读的就是它。
 */
object CastSession : DlnaRenderer.Target {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var activity: WeakReference<CastActivity>? = null

    @Volatile
    private var snap = DlnaRenderer.Snapshot("NO_MEDIA_PRESENT", 0, 0, "")

    /** 应用有页面在前台（App 维护）。不在前台时不接新的投屏：后台打不开页面，也不该出声。 */
    @Volatile
    var foreground = false

    /** 设置里开了就在应用回到前台时开始接收（见 App）。 */
    fun startReceiver(context: Context) {
        app = context.applicationContext
        if (!Store.dlnaReceiver(app.isTv())) return
        DlnaRenderer.start(app, this, Store.dlnaUuid(), "TV Player（${Build.MODEL}）")
    }

    fun stopReceiver() = DlnaRenderer.stop()

    /** 当前的投屏页；退到后台（没到 STARTED）的不算，免得没画面却在后台出声。 */
    private fun current(): CastActivity? = activity?.get()?.takeIf {
        !it.isFinishing && !it.isDestroyed && it.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
    }

    override fun open(url: String, title: String): Boolean {
        if (!foreground) return false
        snap = DlnaRenderer.Snapshot("TRANSITIONING", 0, 0, url)
        main.post {
            val a = current()
            if (a != null) a.load(url, title)
            else app.startActivity(CastActivity.intent(app, url, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return true
    }

    override fun play() {
        main.post { current()?.resume() }
    }

    override fun pause() {
        main.post { current()?.pause() }
    }

    override fun stop() {
        main.post {
            // 先摘掉再关：关闭过程中页面会暂停，那次上报不能再推给手机
            val a = current()
            activity = null
            setSnap(DlnaRenderer.Snapshot("STOPPED", 0, 0, snap.url))
            a?.finish()
        }
    }

    override fun seek(ms: Long) {
        main.post { current()?.seekTo(ms) }
    }

    override fun snapshot() = snap

    fun attach(a: CastActivity) {
        activity = WeakReference(a)
    }

    fun detach(a: CastActivity) {
        if (activity?.get() === a) {
            activity = null
            setSnap(DlnaRenderer.Snapshot("STOPPED", 0, 0, snap.url))
        }
    }

    /** 只认现在挂着的那个页面的上报：换集时正在关掉的旧页面别把旧地址、STOPPED 写回来。 */
    fun report(from: CastActivity, state: String, positionMs: Long, durationMs: Long, url: String) {
        if (activity?.get() !== from) return
        setSnap(DlnaRenderer.Snapshot(state, positionMs, durationMs, url))
    }

    /** 状态变了就推给订阅了事件的手机（进度变化不推，手机自己会轮询）。 */
    private fun setSnap(s: DlnaRenderer.Snapshot) {
        val changed = s.state != snap.state || s.url != snap.url
        snap = s
        if (changed) DlnaRenderer.stateChanged()
    }
}
