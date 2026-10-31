package com.tv.player.core

/**
 * 遥控器按住左右键快进 / 快退：按得越久走得越快，按住期间只挪目标位置，松手才真正跳过去（见 PlayActivity.dispatchKeyEvent）。
 * 速度按真实经过的时间算，不按按键重复的次数算：不同遥控器的重复频率差得很多。
 */
object SeekRamp {
    /** 单按一下跳的距离。 */
    const val TAP_MS = 10_000L

    /** 两次按键事件之间最多按这么久算：卡顿或第一次重复前的长等待不会一下跳出一大截。 */
    private const val MAX_GAP_MS = 200L

    /** 按住 [heldMs] 毫秒时，真实时间每过 1 毫秒，目标位置移动多少毫秒。 */
    fun rate(heldMs: Long, durationMs: Long): Long {
        val stage = when {
            heldMs < 1_500 -> 20L // 刚按住：每秒走 20 秒
            heldMs < 4_000 -> 60L // 按住一会儿：每秒走 1 分钟
            else -> 180L // 一直按着：每秒走 3 分钟
        }
        // 短片不要一下冲到头：每秒最多走全长的 1/20，但不低于每秒 20 秒
        val cap = maxOf(20L, durationMs / 20_000L)
        return minOf(stage, cap)
    }

    /** 从 [from] 出发，距上一个按键事件过了 [gapMs]，新的目标位置（限制在 0..[durationMs]）。 */
    fun advance(from: Long, forward: Boolean, heldMs: Long, gapMs: Long, durationMs: Long): Long {
        val step = rate(heldMs, durationMs) * gapMs.coerceIn(0, MAX_GAP_MS)
        return (from + if (forward) step else -step).coerceIn(0, maxOf(0, durationMs))
    }
}
