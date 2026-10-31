package com.tv.player

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.tv.player.core.CrashLog
import com.tv.player.core.Library
import com.tv.player.core.Store
import com.tv.player.ui.CastSession
import com.tv.player.ui.Episodes

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this) // 最先装上：后面初始化时崩了也要记下来
        Store.init(this)
        Library.init(this)
        Episodes.schedule(this)
        watchForeground()
    }

    /** 投屏接收只在应用前台时开：有页面在前台就开，全部退到后台半分钟后关（页面之间切换时不来回开关）。 */
    private fun watchForeground() {
        val handler = Handler(Looper.getMainLooper())
        val stop = Runnable { CastSession.stopReceiver() }
        var started = 0
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                CastSession.foreground = true
                if (started++ == 0) {
                    handler.removeCallbacks(stop)
                    CastSession.startReceiver(this@App)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (--started == 0) {
                    CastSession.foreground = false
                    handler.postDelayed(stop, 30_000)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
