package com.tv.player.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * 主进程这一边：按需绑定爬虫进程（[SpiderService]），之后一直绑着；关掉开关时 [stop] 解绑，爬虫进程随之退出。
 * 爬虫进程崩了时系统会重新拉起它、再回调连接，调用方这次拿到异常，下次照常。
 */
object SpiderClient {
    private lateinit var app: Context
    @Volatile private var service: ISpiderService? = null
    private var connecting: CompletableDeferred<ISpiderService>? = null
    private var bound = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = ISpiderService.Stub.asInterface(binder)
            synchronized(this@SpiderClient) {
                service = s
                connecting?.complete(s)
                connecting = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }

        override fun onBindingDied(name: ComponentName?) {
            stop()
        }
    }

    fun init(context: Context) {
        app = context.applicationContext
    }

    private suspend fun service(): ISpiderService {
        // 开关关了：排队中的调用不能把爬虫进程又拉起来
        check(Store.jarSpiders()) { "jar 爬虫已关闭" }
        service?.let { return it }
        val pending = synchronized(this) {
            service?.let { return it }
            connecting ?: run {
                if (!bound) bound = app.bindService(Intent(app, SpiderService::class.java), conn, Context.BIND_AUTO_CREATE)
                check(bound) { "爬虫进程起不来" } // 失败时不留下永远等不到的 connecting，下次重新绑
                CompletableDeferred<ISpiderService>().also { connecting = it }
            }
        }
        return try {
            withTimeout(15_000) { pending.await() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 连接被中断（关了开关、绑定失效）或超时，而调用方自己没被取消：当普通错误给它，不然它会把这当成「自己被取消」悄悄结束
            if (kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive != false) throw IllegalStateException("连不上爬虫进程") else throw e
        }
    }

    /** 阻塞到爬虫返回（调用方放在 IO 线程上，见 Spiders.call）。 */
    suspend fun call(jar: String, site: Spiders.Site, method: String, vararg args: String): String {
        val s = service()
        val siteJson = JSONObject().put("key", site.key).put("cls", site.cls).put("ext", site.ext).toString()
        return try {
            s.call(jar, siteJson, method, JSONArray(args.toList()).toString())
        } catch (e: RemoteException) {
            throw IllegalStateException("爬虫进程出错：$e")
        }
    }

    @Synchronized
    fun stop() {
        if (bound) runCatching { app.unbindService(conn) }
        bound = false
        service = null
        connecting?.cancel()
        connecting = null
    }
}
