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
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("用手机给电视输入")
            .setView(view)
            .setNegativeButton("关闭", null)
            .setOnDismissListener { server.stop() }
            .show()
    }

    private fun qr(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(com.google.zxing.EncodeHintType.MARGIN to 2))
        val pixels = IntArray(size * size) { if (matrix[it % size, it / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
