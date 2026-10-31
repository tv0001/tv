package com.tv.player.core

import android.content.Context
import android.os.Build
import com.tv.player.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本机崩溃日志：未捕获的异常写进 files/crash.log（只留最近的几十 KB），再交给系统原来的处理（照常退出）。
 * 不上传，只在设置页或电视的手机网页里看。地址只留域名、token 之类的值打码：里面可能有网盘的临时凭证。
 */
object CrashLog {
    private const val MAX_CHARS = 64 * 1024
    private const val MAX_ENTRY_CHARS = 24 * 1024 // 单条（很深的堆栈、很长的 cause 链）截到这么长，标题行一定留得住
    private const val HEADER = "---- "
    private var file: File? = null

    fun install(context: Context) {
        file = File(context.filesDir, "crash.log")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                record(thread, error)
            } catch (_: Throwable) {
                // 记不下来也不能挡住原来的处理
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** 两个线程同时崩溃时不互相覆盖；先写临时文件再改名，写到一半被杀也不会弄坏旧记录。 */
    @Synchronized
    private fun record(thread: Thread, error: Throwable) {
        val f = file ?: return
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val head = "$time · ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · ${thread.name}"
        val old = if (f.exists()) f.readText() else ""
        val tmp = File(f.parentFile, "crash.log.tmp")
        tmp.writeText(trim(old + entry(head, error.stackTraceToString())))
        tmp.renameTo(f)
    }

    fun read(): String = file?.takeIf { it.exists() }?.readText().orEmpty()

    fun clear() {
        file?.delete()
    }

    /** 记录了几次崩溃。 */
    fun count(text: String = read()): Int = text.lineSequence().count { it.startsWith(HEADER) }

    /** 一条记录：标题行 + 打过码的堆栈（太长的截掉尾部）。 */
    fun entry(head: String, trace: String): String = "$HEADER$head\n${redact(trace).trimEnd().take(MAX_ENTRY_CHARS)}\n\n"

    private val address = Regex("""\b([a-zA-Z][a-zA-Z0-9+.-]*://)(?:[^/\s@]*@)?([^/\s?#:]+)[^\s]*""")
    private val secret = Regex("""(?i)\b(token|sign|signature|auth|authorization|cookie|session|password|passwd|key)(\s*[=:]\s*)[^\s&;,]+""")

    /** 地址只留协议和域名（路径、查询参数里可能有签名和临时凭证，账号密码也去掉）；token=、cookie: 这类值打码。 */
    fun redact(text: String): String =
        secret.replace(address.replace(text) { it.groupValues[1] + it.groupValues[2] + "/…" }) { it.groupValues[1] + it.groupValues[2] + "…" }

    /** 超过上限时从最旧的记录开始丢，留下的每条都是完整的。 */
    fun trim(text: String, max: Int = MAX_CHARS): String {
        if (text.length <= max) return text
        val cut = text.indexOf("\n$HEADER", text.length - max)
        return if (cut < 0) text.takeLast(max) else text.substring(cut + 1)
    }
}
