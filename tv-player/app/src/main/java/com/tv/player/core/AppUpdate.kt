package com.tv.player.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.tv.player.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * In-app update. The version manifest sits next to the subscription files (`app-version.json`):
 * `{"versionCode": 2, "versionName": "1.0.1", "url": "https://…/app.apk", "sha256": "…", "size": 123, "notes": "…", "force": false}`.
 * The downloaded file is checked against `sha256`, and Android itself refuses to install an update that is not
 * signed with the same key as the installed app, so a tampered download cannot replace the app.
 */
object AppUpdate {
    class Info(val code: Int, val name: String, val url: String, val sha256: String, val notes: String, val size: Long, val force: Boolean)

    class Check(val info: Info?, val error: String?)

    fun manifestUrl() = Store.baseUrl().trim().trimEnd('/') + "/app-version.json"

    /** [info] is null when this version is current, was skipped by the user, or the manifest is missing. */
    suspend fun check(respectIgnored: Boolean): Check {
        val r = Http.get(manifestUrl(), timeoutMs = 15_000)
        if (r.status == 404) return Check(null, null)
        if (r.status != 200) return Check(null, r.error ?: "HTTP ${r.status}")
        val o = try {
            JSONObject(r.text)
        } catch (e: JSONException) {
            return Check(null, "更新信息格式不对")
        }
        val code = o.optInt("versionCode", 0)
        if (code <= BuildConfig.VERSION_CODE) return Check(null, null)
        val url = o.optString("url")
        if (!url.startsWith("http://") && !url.startsWith("https://")) return Check(null, "更新信息里没有下载地址")
        val info = Info(
            code, o.optString("versionName").ifEmpty { code.toString() }, url, o.optString("sha256").lowercase(),
            o.optString("notes"), o.optLong("size"), o.optBoolean("force"),
        )
        if (respectIgnored && !info.force && Store.ignoredVersion() == code) return Check(null, null)
        return Check(info, null)
    }

    /** Downloads into the cache directory and verifies the hash. Throws with a readable message on failure. */
    suspend fun download(context: Context, info: Info, onProgress: (done: Long, total: Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "app-${info.code}.apk")
            val request = Request.Builder().url(info.url).header("User-Agent", Http.UA).build()
            val client = Http.client.newBuilder().readTimeout(30, TimeUnit.SECONDS).build()
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) error("下载失败（HTTP ${r.code}）")
                val body = r.body ?: error("下载失败：没有内容")
                val total = body.contentLength().takeIf { it > 0 } ?: info.size
                val digest = MessageDigest.getInstance("SHA-256")
                body.byteStream().use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                if (info.sha256.isNotEmpty() && hex != info.sha256) {
                    file.delete()
                    error("校验失败：下载的文件和发布信息对不上，已丢弃")
                }
            }
            file
        }

    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    /** Opens the system installer; the caller must have checked [canInstall]. */
    fun install(activity: Activity, file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    /** False when this device has no such settings page (some TV firmware); the caller then tells the user where to look. */
    fun openInstallPermissionSettings(activity: Activity): Boolean = try {
        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
        true
    } catch (e: android.content.ActivityNotFoundException) {
        false
    }
}
