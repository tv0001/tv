package com.tv.player.core

import android.app.Application
import android.content.Context
import android.util.Log
import com.github.catvod.crawler.Spider
import com.github.catvod.crawler.SpiderApi
import dalvik.system.DexClassLoader
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 爬虫进程（:spider）这一边：下载、加载 jar，建爬虫实例，调爬虫方法，开本地代理（见 SpiderProxy）。
 * 主进程不加载任何 jar 代码，只经 [SpiderService] 拿爬虫的原始 JSON（见 Spiders）：jar 读不到主进程内存里的东西，
 * 它抛错、甚至把这个进程弄崩，主进程都只是收到一个异常。
 *
 * 加载时**不调用** jar 的 `Init.init`：它会从 jar 里解出一个原生程序并执行（Go 代理），还会弹悬浮球。这里只把
 * Application 填进 Init 单例，让爬虫的 `Init.context()` 能用；依赖那个代理的站点因此用不了。
 */
object SpiderHost {
    private const val TAG = "SpiderHost"

    private lateinit var app: Application

    /** 一个 jar：配置里的总 jar，或站点自己的 jar（站点的 "jar" 字段）。 */
    private class Jar(val loader: ClassLoader, val content: String, @Volatile var checkedAt: Long, val proxy: java.lang.reflect.Method?)

    private val jars = ConcurrentHashMap<String, Jar>()
    private val jarLocks = ConcurrentHashMap<String, Any>()
    @Volatile private var recent = "" // 最近用过的 jar：本地代理先问它（见 proxy）
    private val instances = ConcurrentHashMap<String, Spider>()
    private val creating = ConcurrentHashMap<String, Any>()

    fun init(context: Context) {
        app = context.applicationContext as Application
        com.github.catvod.Runtime.set(app)
    }

    /** 跨进程只能带回少数几种异常，统一成 IllegalStateException（jar 缺类缺方法时抛的 Error 也一样）。 */
    fun call(jar: String, site: JSONObject, method: String, args: JSONArray): String = try {
        val sp = spider(jar, site.getString("key"), site.getString("cls"), site.optString("ext"))
        when (method) {
            "search" -> sp.searchContent(args.getString(0), false)
            "detail" -> sp.detailContent(listOf(args.getString(0)))
            "player" -> sp.playerContent(args.getString(0), args.getString(1), JSONArray(args.getString(2)).let { a -> (0 until a.length()).map(a::getString) })
            else -> throw IllegalArgumentException("未知方法 $method")
        }.orEmpty()
    } catch (e: Throwable) {
        Log.w(TAG, "$method ${site.optString("cls")}", e)
        throw IllegalStateException("爬虫出错：$e")
    }

    /** 取舍下载到的 jar（纯函数，见 [jarFile]）。 */
    internal enum class JarChoice { DOWNLOADED, CACHED, REFUSE }

    /** 配置写了 md5 却拿不到合法的值（md5 地址不是 https、取失败、取回来不是 32 位十六进制）：当成谁都对不上。 */
    internal const val UNRESOLVED_MD5 = "unresolved"

    /** 配置里的 md5（[fetched] 是 md5 地址取回来的内容，取不到时 null）→ 要对的值；没配 md5 时 ""。 */
    internal fun expectedMd5(spec: String, fetched: String?): String {
        val given = spec.split(";md5;").getOrNull(1)?.trim().orEmpty()
        if (given.isEmpty()) return ""
        val value = (if (given.startsWith("http")) fetched.orEmpty() else given).trim().lowercase()
        return if (Regex("[0-9a-f]{32}").matches(value)) value else UNRESOLVED_MD5
    }

    /**
     * 配置给了 md5 时：下载到的对得上就用；对不上时继续用本地已有的那份，本地没有就不加载（被换掉的 jar 是能在本应用里跑的代码）。
     * 没给 md5 时用下载到的（只从 https 下，见 [jarFile]）。
     */
    internal fun choose(expected: String, cached: String, downloaded: String): JarChoice = when {
        expected.isEmpty() || downloaded == expected -> JarChoice.DOWNLOADED
        cached.isNotEmpty() -> JarChoice.CACHED
        else -> JarChoice.REFUSE
    }

    /**
     * jar 下载到 code_cache（Android 14 起动态加载的文件必须只读），按地址存，只从 https 下（明文 http 在同一网络里就能被换成别的代码）。
     * 配置里的 md5（可能是个地址，也只认 https；配了却拿不到时见 [expectedMd5]）和本地的一样就不下；不一样就重新下，
     * 按 [choose] 取舍。不带 md5 时半天更新一次。
     * 下载失败时用本地已有的。返回文件和它内容的 md5（内容变了才重新加载）。
     */
    private fun jarFile(spec: String): Pair<File, String> {
        val url = spec.substringBefore(';').trim()
        require(url.startsWith("https://")) { "jar 地址不是 https，不加载：$url" }
        val given = spec.split(";md5;").getOrNull(1)?.trim().orEmpty()
        val md5 = expectedMd5(spec, if (given.startsWith("https://")) runCatching { get(given).toString(Charsets.UTF_8) }.getOrNull() else null)
        val dir = File(app.codeCacheDir, "spider").apply { mkdirs() }
        val file = File(dir, md5Of(url.toByteArray()) + ".jar")
        val cached = if (file.exists()) md5Of(file.readBytes()) else ""
        if (cached.isNotEmpty() && (if (md5.isNotEmpty()) cached == md5 else System.currentTimeMillis() - file.lastModified() < 12 * 3600_000L)) return file to cached
        val bytes = try {
            get(url)
        } catch (e: Exception) {
            if (cached.isNotEmpty()) return file to cached
            throw e
        }
        val got = md5Of(bytes)
        when (choose(md5, cached, got)) {
            JarChoice.REFUSE -> throw IllegalStateException("jar 的 md5（$got）和配置里的（$md5）不一致，不加载")
            JarChoice.CACHED -> {
                Log.w(TAG, "jar 的 md5（$got）和配置里的（$md5）不一致，继续用本地的")
                return file to cached
            }
            JarChoice.DOWNLOADED -> Unit
        }
        if (got == cached) {
            file.setLastModified(System.currentTimeMillis())
            return file to cached
        }
        val tmp = File(dir, file.name + ".tmp")
        tmp.delete()
        tmp.writeBytes(bytes)
        tmp.setReadOnly()
        file.delete()
        check(tmp.renameTo(file)) { "保存 jar 失败" }
        return file to got
    }

    private fun get(url: String): ByteArray = Http.client.newCall(Request.Builder().url(url).header("User-Agent", Http.UA).build()).execute().use { r ->
        check(r.isSuccessful) { "下载 jar 失败：HTTP ${r.code}" }
        r.body!!.bytes()
    }

    private fun md5Of(b: ByteArray) = MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }

    /** 已加载的 jar 十分钟内不再检查更新；检查或下载失败时继续用已加载的。每个 jar 各自加锁。 */
    private fun classLoader(spec: String): ClassLoader {
        // 按地址加锁：同一地址换了 md5 的两个配置串会下载到同一个文件
        synchronized(jarLocks.getOrPut(spec.substringBefore(';').trim()) { Any() }) { return load(spec) }
    }

    private fun load(spec: String): ClassLoader {
        val have = jars[spec]
        if (have != null && System.currentTimeMillis() - have.checkedAt < 10 * 60_000L) return have.loader
        val (file, content) = try {
            jarFile(spec)
        } catch (e: Exception) {
            have?.let { Log.w(TAG, "jar 更新失败，继续用已加载的", e); it.checkedAt = System.currentTimeMillis(); return it.loader }
            throw e
        }
        if (have != null && have.content == content) {
            have.checkedAt = System.currentTimeMillis()
            return have.loader
        }
        val l = DexClassLoader(file.path, File(app.codeCacheDir, "spider-oat").apply { mkdirs() }.path, null, SpiderHost::class.java.classLoader)
        // 只填 Init 单例里的 Application 字段，不调 Init.init（见类注释）
        runCatching {
            val init = l.loadClass("com.github.catvod.spider.Init")
            val single = init.getMethod("get").invoke(null)
            init.declaredFields.filter { !Modifier.isStatic(it.modifiers) && it.type.isAssignableFrom(Application::class.java) && it.type != Any::class.java }
                .forEach { it.isAccessible = true; it.set(single, app) }
        }.onFailure { Log.w(TAG, "Init 设置失败", it) }
        // 这个 jar 换了新版本：它的旧实例作废
        instances.keys.filter { it.startsWith("$spec|") }.forEach { k -> instances.remove(k)?.let { runCatching { it.destroy() } } }
        val proxy = runCatching { l.loadClass("com.github.catvod.spider.Proxy").getMethod("proxy", Map::class.java) }.getOrNull()
        jars[spec] = Jar(l, content, System.currentTimeMillis(), proxy)
        // 同一地址的旧配置串（订阅更新改了 md5）：旧 jar 不再参与本地代理分派
        val url = spec.substringBefore(';').trim()
        jars.keys.filter { it != spec && it.substringBefore(';').trim() == url }.forEach { jars.remove(it) }
        // 本地代理要在爬虫第一次问端口之前起来（见 SpiderProxy）
        SpiderProxy.start()
        return l
    }

    /** 本地代理的请求交给哪个 jar：照 TVBox（FongMi），先问最近用过的，它不管（返回 null）再挨个问别的。 */
    fun proxy(params: Map<String, String>): Array<Any?>? {
        val order = listOfNotNull(jars[recent]) + jars.filterKeys { it != recent }.values
        for (j in order) {
            val m = j.proxy ?: continue
            @Suppress("UNCHECKED_CAST")
            val r = runCatching { m.invoke(null, params) as? Array<Any?> }.onFailure { Log.w(TAG, "proxy", it) }.getOrNull()
            if (r != null) return r
        }
        return null
    }

    /** 每个站点各自加锁初始化（init 常要联网好几秒，不能让别的站点一起等）；订阅更新改了类或 ext 时重新建。 */
    private fun spider(jar: String, key: String, cls: String, ext: String): Spider {
        val l = classLoader(jar)
        recent = jar
        SpiderProxy.bypassLocal()
        val id = "$jar|$key|$cls|$ext"
        instances[id]?.let { return it }
        synchronized(creating.getOrPut(id) { Any() }) {
            instances[id]?.let { return it }
            val sp = l.loadClass("com.github.catvod.spider.$cls").getDeclaredConstructor().newInstance() as Spider
            sp.initApi(SpiderApi())
            sp.init(app, ext)
            instances[id] = sp
            return sp
        }
    }
}
