package com.tv.player.ui

import android.graphics.BitmapFactory
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
import com.tv.player.core.Quark
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
                if (!done) onCancel()
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
}
