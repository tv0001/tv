package com.tv.player.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.tv.player.R
import com.tv.player.core.Http
import com.tv.player.core.Live
import com.tv.player.core.PhoneServer
import com.tv.player.core.Probe
import com.tv.player.core.NetStore
import com.tv.player.core.Store
import com.tv.player.data.VodSource

/** "Type on your phone": a QR code to a page served by the TV; what is typed there arrives here. */
object PhonePush {
    fun show(activity: AppCompatActivity, onSearch: (String) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        var dialog: androidx.appcompat.app.AlertDialog? = null
        val server = PhoneServer(object : PhoneServer.Listener {
            override fun onSearch(keyword: String) {
                main.post {
                    dialog?.dismiss()
                    onSearch(keyword)
                }
            }

            override fun onKey(name: String): String = RemoteControl.press(name)

            override suspend fun onVod(name: String, api: String): String {
                val clean = api.trim()
                if (!clean.startsWith("http://") && !clean.startsWith("https://")) return "请输入 http(s) 开头的接口地址"
                val key = clean.trimEnd('/')
                val r = Probe.vod(VodSource(key, name.ifBlank { clean }, clean))
                if (!r.ok) return "没通过检测，没有添加：${r.error}"
                Store.addCustomSite(name, clean)
                main.post { Toast.makeText(activity, "手机添加了点播源：${name.ifBlank { clean }}", Toast.LENGTH_LONG).show() }
                return "已添加：${r.describe()}"
            }

            override suspend fun onLive(name: String, url: String): String {
                val clean = url.trim()
                if (!clean.startsWith("http://") && !clean.startsWith("https://")) return "请输入 http(s) 开头的地址"
                val r = Http.get(clean, timeoutMs = 25_000)
                val count = if (r.status == 200) Live.parse(r.text).size else 0
                if (count == 0) return if (r.status == 200) "地址能打开，但没解析出频道，需要 TXT 或 M3U 格式" else "下载失败：${r.error ?: "HTTP ${r.status}"}"
                Store.addCustomLive(name, clean, r.text)
                main.post { Toast.makeText(activity, "手机添加了直播源：$count 个频道", Toast.LENGTH_LONG).show() }
                return "已添加：$count 个频道"
            }

            override suspend fun onStorage(kind: String, name: String, address: String, user: String, pass: String): String {
                val error = NetStore.add(if (kind == "smb") "smb" else "webdav", name, address, user, pass)
                if (error != null) return "没有添加：$error"
                main.post { Toast.makeText(activity, "手机添加了网络存储，在「我的」→「网络存储」里打开", Toast.LENGTH_LONG).show() }
                return "已添加，在电视上「我的」→「网络存储」里打开"
            }
        })
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_phone_push, null)
        val url = if (server.start()) server.url() else null
        if (url == null) {
            server.stop()
            view.findViewById<TextView>(R.id.pushUrl).text = "没有连上局域网（Wi-Fi 或网线），没法使用"
            view.findViewById<ImageView>(R.id.pushQr).visibility = android.view.View.GONE
        } else {
            view.findViewById<TextView>(R.id.pushUrl).text = url
            view.findViewById<ImageView>(R.id.pushQr).setImageBitmap(qr(url, 600))
        }
        // Closing the dialog normally stops the page. "保持遥控" keeps it up for a while, so the phone can drive the
        // screen once the dialog is gone (a dialog covers the screen, so the remote's keys could not reach it).
        var keepForRemote = false
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("用手机输入或遥控电视")
            .setView(view)
            .setNeutralButton("保持遥控 30 分钟") { _, _ ->
                keepForRemote = true
                Toast.makeText(activity, "30 分钟内可以用手机遥控电视", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("关闭", null)
            .setOnDismissListener {
                if (keepForRemote) main.postDelayed({ server.stop() }, REMOTE_MS) else server.stop()
            }
            .show()
    }

    private const val REMOTE_MS = 30 * 60_000L

    private fun qr(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(com.google.zxing.EncodeHintType.MARGIN to 2))
        val pixels = IntArray(size * size) { if (matrix[it % size, it / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
