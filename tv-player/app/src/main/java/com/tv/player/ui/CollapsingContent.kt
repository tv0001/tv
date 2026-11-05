package com.tv.player.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import androidx.core.view.NestedScrollingParent3
import androidx.core.view.NestedScrollingParentHelper
import androidx.core.view.ViewCompat
import kotlin.math.abs

/**
 * 手机播放页小窗下面的那一栏（简介、线路、选集），和 B 站详情页一样：
 * - 里面的列表竖着滚动时，先把滚动量交给 [onPreScroll]：竖屏视频的播放器比 16:9 高，往上滚先把播放器收矮，
 *   滚回顶部再往下拉又把它撑高。[onPreScroll] 返回用掉的距离，和 dy 同号；剩下的列表照常滚。
 * - 在这一栏往下拖：从 [list] 里开始拖、而列表没在顶上时，先让列表自己往回滚；其余地方（把手、简介、按钮）
 *   或列表已经在顶上时，[onPullStart] 同意的话，这次拖动整个交给 [onPull]（每次手指
 *   往下移动的像素，往回是负数），松手时调 [onPullEnd]（是不是正常松手、竖直速度 px/s）。用来撑高小窗、再往下就
 *   拖着这一栏进竖屏全屏。
 */
class CollapsingContent @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs), NestedScrollingParent3 {
    var onPreScroll: ((dy: Int, target: View) -> Int)? = null
    var list: View? = null
    var onPullStart: (() -> Boolean)? = null
    var onPull: ((dy: Float) -> Unit)? = null
    var onPullEnd: ((up: Boolean, velocityY: Float) -> Unit)? = null

    private val helper = NestedScrollingParentHelper(this)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val velocity = VelocityTracker.obtain()
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var pulling = false

    // ---- 往下拖这一栏 ----------------------------------------------------------------------------------------------

    /** 手指往下超过触摸阈值、而且比横向动得多，并且 [onPullStart] 同意，就接管这次拖动。坐标用屏幕坐标：这一栏自己会跟着动。 */
    private fun track(e: MotionEvent): Boolean {
        MotionEvent.obtain(e).also {
            it.setLocation(e.rawX, e.rawY)
            velocity.addMovement(it)
            it.recycle()
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                velocity.clear()
                downX = e.rawX
                downY = e.rawY
                lastY = e.rawY
                pulling = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pulling) {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (dy > slop && dy > abs(dx) && !listScrollsFirst() && onPullStart?.invoke() == true) {
                        pulling = true
                        lastY = e.rawY
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                } else {
                    onPull?.invoke(e.rawY - lastY)
                    lastY = e.rawY
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (pulling) {
                pulling = false
                velocity.computeCurrentVelocity(1000)
                onPullEnd?.invoke(e.actionMasked == MotionEvent.ACTION_UP, velocity.yVelocity)
                return true
            }
        }
        return pulling
    }

    /** 手指是从列表里按下的、列表又没在顶上：这一下往下拖归列表（先滚回去）。 */
    private fun listScrollsFirst(): Boolean {
        val l = list ?: return false
        if (!l.canScrollVertically(-1)) return false
        val at = IntArray(2).also { l.getLocationOnScreen(it) }
        return downX >= at[0] && downX < at[0] + l.width && downY >= at[1] && downY < at[1] + l.height
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = track(ev)

    // 落在空白处（没有子视图接）的按下也要收下，后面的移动才会送到这里
    override fun onTouchEvent(event: MotionEvent): Boolean {
        track(event)
        return true
    }

    // ---- 列表滚动先收放小窗 ------------------------------------------------------------------------------------------

    override fun onStartNestedScroll(child: View, target: View, axes: Int, type: Int) =
        onPreScroll != null && axes and ViewCompat.SCROLL_AXIS_VERTICAL != 0

    override fun onNestedScrollAccepted(child: View, target: View, axes: Int, type: Int) = helper.onNestedScrollAccepted(child, target, axes, type)

    override fun onStopNestedScroll(target: View, type: Int) = helper.onStopNestedScroll(target, type)

    override fun onNestedPreScroll(target: View, dx: Int, dy: Int, consumed: IntArray, type: Int) {
        if (dy != 0) consumed[1] = onPreScroll?.invoke(dy, target) ?: 0
    }

    override fun onNestedScroll(target: View, dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int, type: Int, consumed: IntArray) = Unit

    override fun onNestedScroll(target: View, dxConsumed: Int, dyConsumed: Int, dxUnconsumed: Int, dyUnconsumed: Int, type: Int) = Unit

    override fun getNestedScrollAxes() = helper.nestedScrollAxes
}
