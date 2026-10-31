package com.tv.player.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 自动匹配帧率：片源帧率和电视刷新率不成整数倍时（24 帧的电影放在 60Hz 上），画面会有规律地一顿一顿。
 * 从电视支持的显示模式里挑一个同分辨率、刷新率是帧率整数倍的，播放时切过去（Kodi、Just Player 也是这么做的）。
 */
object FrameRateMatch {
    class Mode(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

    /** 常见的帧率：估出来的值离它们很近就按它们算。 */
    private val standard = listOf(23.976f, 24f, 25f, 29.97f, 30f, 48f, 50f, 59.94f, 60f)

    /** 适合 [fps] 的模式 id；现在的模式已经合适、或者没有合适的，返回 null（不切）。 */
    fun pick(fps: Float, current: Mode, modes: List<Mode>): Int? {
        if (fps < 10f || fps > 125f) return null
        if (fits(current.refreshRate, fps)) return null
        // 倍数最小的优先：24 帧选 24Hz，没有再选 48Hz
        return modes.filter { it.width == current.width && it.height == current.height && fits(it.refreshRate, fps) }
            .minByOrNull { it.refreshRate }?.id
    }

    /** 刷新率是帧率的整数倍。容差 0.05%：23.976 和 24、59.94 和 60 要分得开。 */
    fun fits(refresh: Float, fps: Float): Boolean {
        val ratio = refresh / fps
        val n = ratio.roundToInt()
        return n >= 1 && abs(ratio - n) / n < 0.0005f
    }

    /** 估出来的帧率靠近常见值（0.3% 以内）就取常见值，否则原样返回。 */
    fun snap(fps: Float): Float = standard.minByOrNull { abs(it - fps) }?.takeIf { abs(it - fps) / it < 0.003f } ?: fps

    /**
     * 从解码出来的每一帧的时间戳估帧率（有的 TS 流不带帧率信息）。播放线程上逐帧喂，攒够一批后取相邻帧间隔的中位数，
     * 跳过的帧、seek 造成的大间隔不影响结果。
     */
    class Estimator(private val frames: Int = 60) {
        private val deltas = ArrayList<Long>()
        private var last = -1L
        private var done = false

        /** 喂一帧的时间戳（微秒）；估好时返回帧率，之后都返回 null，直到 [reset]。 */
        @Synchronized
        fun add(presentationUs: Long): Float? {
            if (done) return null
            val prev = last
            last = presentationUs
            if (prev < 0) return null
            val d = presentationUs - prev
            if (d in 1..200_000) deltas.add(d) // 超过 0.2 秒的间隔（seek、丢帧）不算
            if (deltas.size < frames) return null
            done = true
            val median = deltas.sorted()[deltas.size / 2]
            return snap(1_000_000f / median)
        }

        @Synchronized
        fun reset() {
            deltas.clear()
            last = -1
            done = false
        }
    }
}
