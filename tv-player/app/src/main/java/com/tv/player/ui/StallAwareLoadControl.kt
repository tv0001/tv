package com.tv.player.ui

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import com.tv.player.core.StallPolicy

/**
 * [DefaultLoadControl] with one change: after a stall, playback resumes only once [StallPolicy.resumeMs] are buffered
 * (never waiting more than [StallPolicy.MAX_EXTRA_WAIT_MS] for it). Everything else is the wrapped control's, forwarded
 * method by method: the player calls these, and a default method left unforwarded would silently do nothing.
 */
@UnstableApi
class StallAwareLoadControl(private val base: DefaultLoadControl, private val policy: StallPolicy) : LoadControl {
    override fun onPrepared(playerId: PlayerId) = base.onPrepared(playerId)

    override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, trackSelections: Array<ExoTrackSelection?>) =
        base.onTracksSelected(parameters, trackGroups, trackSelections)

    override fun onStopped(playerId: PlayerId) = base.onStopped(playerId)

    override fun onReleased(playerId: PlayerId) = base.onReleased(playerId)

    override fun getAllocator(): Allocator = base.allocator

    override fun getBackBufferDurationUs(playerId: PlayerId): Long = base.getBackBufferDurationUs(playerId)

    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = base.retainBackBufferFromKeyframe(playerId)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean = base.shouldContinueLoading(parameters)

    override fun shouldContinuePreloading(timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, bufferedDurationUs: Long): Boolean =
        base.shouldContinuePreloading(timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        if (!base.shouldStartPlayback(parameters)) return false
        // A live stream has no spare time to wait: the window moves on and the player would be thrown out of it.
        if (parameters.targetLiveOffsetUs != C.TIME_UNSET) return true
        if (!parameters.rebuffering || policy.stalls == 0) return true
        val waited = if (parameters.lastRebufferRealtimeMs == C.TIME_UNSET) 0L else SystemClock.elapsedRealtime() - parameters.lastRebufferRealtimeMs
        if (waited >= StallPolicy.MAX_EXTRA_WAIT_MS) return true
        val buffered = Util.getPlayoutDurationForMediaDuration(parameters.bufferedDurationUs, parameters.playbackSpeed)
        return buffered >= policy.resumeMs() * 1_000
    }
}
