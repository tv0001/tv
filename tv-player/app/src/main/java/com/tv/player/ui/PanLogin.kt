package com.tv.player.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tv.player.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.tv.player.core.Quark
import com.tv.player.core.QuarkWeb
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The 夸克网盘 login: a QR code for the phone's Quark app, polled until the phone approves. */
object PanLogin {
    private const val POLL_MS = 2_000L
    private const val GIVE_UP_MS = 5 * 60_000L

    fun show(activity: AppCompatActivity, onDone: () -> Unit, onCancel: () -> Unit = {}) {
        val image = ImageView(activity).apply { adjustViewBounds = true }
        val hint = TextView(activity).apply {
            text = "正在取二维码…"
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(activity, R.color.on_surface_variant))
            setPadding(0, activity.dp(12), 0, 0)
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(activity.dp(24), activity.dp(8), activity.dp(24), 0)
            addView(image, LinearLayout.LayoutParams(activity.dp(240), activity.dp(240)))
            addView(hint)
        }
        var job: Job? = null
        var done = false
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("登录夸克网盘")
            .setView(box)
            .setNegativeButton("取消", null)
            .setOnDismissListener {
                job?.cancel()
                if (!done && !activity.isFinishing) onCancel()
            }
            .show()
        // The remote's focus must land on the button, not the panel around it, so Back/OK work at once.
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.let { b -> b.post { b.requestFocus() } }
        job = activity.lifecycleScope.launch {
            try {
                val login = Quark.loginStart()
                image.setImageBitmap(BitmapFactory.decodeByteArray(login.qrJpeg, 0, login.qrJpeg.size))
                hint.text = "打开手机上的夸克 App，扫一扫这个二维码并确认"
                val until = System.currentTimeMillis() + GIVE_UP_MS
                while (System.currentTimeMillis() < until) {
                    delay(POLL_MS)
                    if (Quark.loginPoll(login.queryToken)) {
                        done = true
                        dialog.dismiss()
                        Toast.makeText(activity, "夸克网盘已登录", Toast.LENGTH_SHORT).show()
                        onDone()
                        return@launch
                    }
                }
                hint.text = "二维码已过期，关掉后重新打开"
            } catch (e: Quark.ApiException) {
                hint.text = e.message
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                hint.text = "连不上夸克：${e.message ?: "网络不通"}"
            }
        }
    }

    /** The web login: what saving a share into your drive needs (the TV login cannot do it). */
    fun showWeb(activity: AppCompatActivity, onDone: () -> Unit, onCancel: () -> Unit = {}) {
        val image = ImageView(activity).apply { adjustViewBounds = true }
        val hint = TextView(activity).apply {
            text = "正在取二维码…"
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(activity, R.color.on_surface_variant))
            setPadding(0, activity.dp(12), 0, 0)
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(activity.dp(24), activity.dp(8), activity.dp(24), 0)
            addView(image, LinearLayout.LayoutParams(activity.dp(240), activity.dp(240)))
            addView(hint)
        }
        var job: Job? = null
        var done = false
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("登录夸克网盘（转存分享用）")
            .setView(box)
            .setNegativeButton("取消", null)
            .setOnDismissListener {
                job?.cancel()
                if (!done && !activity.isFinishing) onCancel()
            }
            .show()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.let { b -> b.post { b.requestFocus() } }
        job = activity.lifecycleScope.launch {
            try {
                val login = QuarkWeb.loginStart()
                image.setImageBitmap(qr(login.qrUrl, 480))
                hint.text = "打开手机上的夸克 App，扫一扫这个二维码并确认"
                val until = System.currentTimeMillis() + GIVE_UP_MS
                while (System.currentTimeMillis() < until) {
                    delay(POLL_MS)
                    if (QuarkWeb.loginPoll(login)) {
                        done = true
                        dialog.dismiss()
                        Toast.makeText(activity, "夸克网盘已登录", Toast.LENGTH_SHORT).show()
                        onDone()
                        return@launch
                    }
                }
                hint.text = "二维码已过期，关掉后重新打开"
            } catch (e: QuarkWeb.ApiException) {
                hint.text = e.message
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                hint.text = "连不上夸克：${e.message ?: "网络不通"}"
            }
        }
    }

    private fun qr(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2))
        val pixels = IntArray(size * size) { if (matrix[it % size, it / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }
}
