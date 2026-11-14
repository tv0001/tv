package com.tv.player

import android.app.Activity
import android.app.Application
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.tv.player.core.CrashLog
import com.tv.player.core.Http
import com.tv.player.core.LegacyTls
import com.tv.player.core.PlayLog
import com.tv.player.core.Library
import com.tv.player.core.Spiders
import com.tv.player.core.Store
import com.tv.player.ui.CastSession
import com.tv.player.ui.Episodes

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 爬虫进程（见 SpiderService）只跑第三方 jar：补根证书就够了，数据库、投屏、网络回调这些主进程的东西都不碰
        if (processName().endsWith(":spider")) {
            LegacyTls.install(this)
            return
        }
        CrashLog.install(this) // 最先装上：后面初始化时崩了也要记下来
        PlayLog.install(this)
        LegacyTls.install(this) // 兼容包在 Android 7.1 以下补根证书，其余情况什么都不做
        // 换了网络（换 Wi-Fi、断线重连）：旧网络查到的域名地址不一定还能用，池里旧网络上的空闲连接也别再拿来用
        // （请求发出去才发现连接已死，要等满读超时）
        runCatching {
            val cm = ContextCompat.getSystemService(this, android.net.ConnectivityManager::class.java)
            val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    Http.dns.clear()
                    Http.client.connectionPool.evictAll()
                }
            }
            if (Build.VERSION.SDK_INT >= 24) cm?.registerDefaultNetworkCallback(callback)
            // Android 7.0 以下（兼容包）没有「默认网络」回调：改听所有能上网的网络，多清几次也无妨
            else cm?.registerNetworkCallback(
                android.net.NetworkRequest.Builder().addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback,
            )
        }
        Store.init(this)
        Spiders.init(this)
        Library.init(this)
        Episodes.schedule(this)
        watchForeground()
    }

    private fun processName(): String =
        if (Build.VERSION.SDK_INT >= 28) getProcessName()
        else runCatching { java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n') }.getOrDefault("")

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
