package com.tv.player.core

import android.content.Context
import android.os.Build
import com.tv.player.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 播放日志：卡顿和每次播放的小结写进 files/play.log（只留最近的几十 KB），在设置页或电视的手机网页里看，
 * 用来查某台电视上为什么老是要加载。不上传。地址按崩溃日志的规则打码（只留域名）。
 * 写文件放在后台线程，播放页不等它。
 */
object PlayLog {
    private const val MAX_CHARS = 48 * 1024
    private var file: File? = null
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "tvp-playlog").apply { isDaemon = true } }

    fun install(context: Context) {
        file = File(context.filesDir, "play.log")
    }

    /** 追加一条：标题行（时间、版本、机型）+ 正文。格式和崩溃日志一样，超长时从最旧的整条丢起。 */
    fun add(title: String, body: String) {
        val f = file ?: return
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        val head = "$time · $title · ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
        val entry = "---- $head\n${CrashLog.redact(body).trimEnd()}\n\n"
        writer.execute {
            try {
                CrashLog.append(f, entry, MAX_CHARS)
            } catch (_: Exception) {
                // 日志写不下来不影响播放
            }
        }
    }

    fun read(): String = file?.takeIf { it.exists() }?.readText().orEmpty()

    /** 排在还没写完的记录后面删，并等它删完：调用方接着就要看日志是不是空了。文件很小，等不了几毫秒。 */
    fun clear() {
        val f = file ?: return
        runCatching { writer.submit { f.delete() }.get() }
    }

    fun isEmpty(): Boolean = file?.let { !it.exists() || it.length() == 0L } ?: true
}
