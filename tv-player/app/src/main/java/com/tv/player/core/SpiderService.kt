package com.tv.player.core

import android.app.Service
import android.content.Intent
import android.os.IBinder
import org.json.JSONArray
import org.json.JSONObject

/** 爬虫进程（清单里 android:process=":spider"）对主进程开的口，见 SpiderHost / SpiderClient。 */
class SpiderService : Service() {
    private val binder = object : ISpiderService.Stub() {
        override fun call(jar: String, site: String, method: String, args: String): String =
            SpiderHost.call(jar, JSONObject(site), method, JSONArray(args))
    }

    override fun onCreate() {
        super.onCreate()
        SpiderHost.init(this)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** 主进程解绑（关掉开关，或主进程没了）：整个进程退出，jar 代码和本地代理都不留。 */
    override fun onUnbind(intent: Intent?): Boolean {
        stopSelf()
        android.os.Process.killProcess(android.os.Process.myPid())
        return false
    }
}
