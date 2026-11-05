package com.tv.player.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

private fun View.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

/** 渐显：已经在显示（或正在渐隐）时只把透明度拉回来，不从 0 重来，连着刷新不会闪。 */
private fun View.fadeIn(ms: Long = 120) {
    animate().cancel()
    if (visibility != View.VISIBLE) {
        alpha = 0f
        visibility = View.VISIBLE
    }
    animate().alpha(1f).setDuration(ms).start()
}

private fun View.fadeOut(ms: Long = 180) {
    if (visibility != View.VISIBLE) return
    animate().cancel()
    animate().alpha(0f).setDuration(ms).withEndAction { visibility = View.GONE }.start()
}

/**
 * 播放画面中间的提示胶囊：图标 + 文字 + 一条细进度条（B 站、腾讯视频拖亮度 / 音量 / 进度时的样子）。
 * 只给文字时就是一条普通提示。
 */
class GestureHud(context: Context) : LinearLayout(context) {
    private val icon = ImageView(context)
    private val label = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 17f
        gravity = Gravity.CENTER
    }
    private val bar = HudBar(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = GONE
        setPadding(dp(18), dp(10), dp(18), dp(10))
        background = GradientDrawable().apply {
            setColor(0xB3000000.toInt())
            cornerRadius = dp(10).toFloat()
        }
        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(icon, LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(8) })
                addView(label)
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
        )
        addView(bar, LayoutParams(dp(150), dp(3)).apply { topMargin = dp(8) })
    }

    /** [progress] 在 0..1 之间才显示进度条；[iconRes] 为 0 不显示图标。 */
    fun show(text: CharSequence, iconRes: Int = 0, progress: Float = -1f) {
        label.text = text
        icon.visibility = if (iconRes != 0) VISIBLE else GONE
        if (iconRes != 0) icon.setImageResource(iconRes)
        bar.visibility = if (progress >= 0f) VISIBLE else GONE
        if (progress >= 0f) bar.progress = progress.coerceIn(0f, 1f)
        fadeIn()
    }

    fun hide() = fadeOut()
}

/** 细进度条：自己画两段圆角条，不用 ProgressBar（主题不同颜色不好控制）。 */
private class HudBar(context: Context) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4DFFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val rect = RectF()

    var progress = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, track)
        rect.right = width * progress
        if (rect.right > 0f) canvas.drawRoundRect(rect, r, r, fill)
    }
}

/** 长按倍速时顶上的小胶囊：三个箭头依次亮起 + 「2× 倍速播放中」（B 站的样子），不挡画面中间。 */
class SpeedTip(context: Context) : LinearLayout(context) {
    private val arrows = List(3) { ImageView(context).apply { setImageResource(com.tv.player.R.drawable.ic_play_small); setColorFilter(Color.WHITE) } }
    private val label = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 14f
    }
    private var anim: ValueAnimator? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
        setPadding(dp(12), dp(6), dp(14), dp(6))
        background = GradientDrawable().apply {
            setColor(0x99000000.toInt())
            cornerRadius = dp(16).toFloat()
        }
        // 箭头之间挤一点，看起来是连着的一串
        arrows.forEachIndexed { i, a -> addView(a, LayoutParams(dp(14), dp(14)).apply { if (i > 0) marginStart = -dp(5) }) }
        addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
    }

    fun show(text: CharSequence) {
        label.text = text
        fadeIn()
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 3f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { v ->
                val lit = (v.animatedValue as Float).toInt()
                arrows.forEachIndexed { i, a -> a.alpha = if (i <= lit) 1f else 0.3f }
            }
            start()
        }
    }

    fun hide() {
        anim?.cancel()
        anim = null
        fadeOut()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        super.onDetachedFromWindow()
    }
}

/**
 * 双击两侧快进 / 快退时，那一侧贴边的半个椭圆 + 箭头和累计秒数（YouTube、爱奇艺的样子）。
 * 宽度由调用方按画面宽度设；高度铺满。
 */
class TapSeekRipple(context: Context, forward: Boolean) : FrameLayout(context) {
    private val icon = ImageView(context).apply {
        setImageResource(if (forward) com.tv.player.R.drawable.ic_hud_forward else com.tv.player.R.drawable.ic_hud_rewind)
    }
    private val label = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 14f
        setShadowLayer(dp(2).toFloat(), 0f, 0f, 0x80000000.toInt())
    }

    init {
        visibility = GONE
        background = EdgeArc(forward)
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(icon, LinearLayout.LayoutParams(dp(28), dp(28)))
                // 竖排的 LinearLayout 默认让子 View 撑满宽度，外层又是包内容，文字会被压成和图标一样窄
                addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
    }

    fun show(text: CharSequence) {
        label.text = text
        fadeIn(100)
        // 每点一下箭头弹一下，看得出这一下点上了
        icon.animate().cancel()
        icon.scaleX = 0.7f
        icon.scaleY = 0.7f
        icon.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
    }

    fun hide() = fadeOut(250)
}

/** 圆心在屏幕边上的半个椭圆。 */
private class EdgeArc(private val right: Boolean) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val extra = b.height() * 0.12f
        if (right) rect.set(b.left.toFloat(), b.top - extra, b.left + 2 * w, b.bottom + extra)
        else rect.set(b.left - w, b.top - extra, b.right.toFloat(), b.bottom + extra)
        canvas.drawOval(rect, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha * 0x33 / 255
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
