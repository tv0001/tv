package com.tv.player.ui

import android.content.Context
import android.os.Handler
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * 播放器的渲染器：首选的视频解码器起不来时换一个，不至于整条线路失败；设备没有 AC3 / EAC3 / DTS 等音频的硬解时
 * 用 ffmpeg 软解（排在系统解码器之后），不至于有画面没声音。视频不用 ffmpeg 软解：扩展里的视频解码是实验性的，
 * 盒子上也太慢。
 */
class AudioFallbackRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    init {
        setEnableDecoderFallback(true)
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) = super.buildVideoRenderers(
        context, EXTENSION_RENDERER_MODE_OFF, mediaCodecSelector, enableDecoderFallback,
        eventHandler, eventListener, allowedVideoJoiningTimeMs, out,
    )
}
