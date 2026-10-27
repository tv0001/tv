package com.tv.player.core

/**
 * How long to build up the buffer before playing on after a stall. The player resumes as soon as 2 s are buffered, which is
 * quick on a good line and, on a weak one, only runs it dry again a moment later: stutter. So the more it has stalled in
 * this viewing, the more it gathers before resuming (a few longer waits instead of many short ones); an episode that
 * plays through without a stall takes one step back.
 */
class StallPolicy {
    @Volatile // written on the main thread, read by the player's own thread
    var stalls = 0
        private set

    fun onStall() {
        stalls++
    }

    fun onSmoothEpisode() {
        if (stalls > 0) stalls--
    }

    /** Media to have buffered before playing on after a stall; the player's own 2 s while nothing has stalled. */
    fun resumeMs(): Long = if (stalls == 0) BASE_MS else (BASE_MS + STEP_MS * stalls).coerceAtMost(MAX_MS)

    companion object {
        const val BASE_MS = 2_000L
        const val STEP_MS = 1_500L
        const val MAX_MS = 7_000L

        /** However much is asked for, a stall never lasts longer than this on its account: the end of a stream cannot buffer more. */
        const val MAX_EXTRA_WAIT_MS = 8_000L
    }
}
