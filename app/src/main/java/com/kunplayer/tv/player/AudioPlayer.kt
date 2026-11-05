package com.kunplayer.tv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.kunplayer.tv.audio.AudioBus
import com.kunplayer.tv.audio.AudioTap
import com.kunplayer.tv.net.BiliHttp

/**
 * 纯音频播放层。
 *
 * 四个要点：
 *   1. OkHttpDataSource 与接口请求共用同一个 OkHttpClient，Referer 逐请求挂上——
 *      这是 CDN 不返 403 的唯一原因，也是 WebView 方案做不到的事；
 *   2. setAudioAttributes(..., handleAudioFocus = true) 让系统在来电/其他 App 播放时
 *      自动降音或暂停；
 *   3. 走 ProgressiveMediaSource 而不是 DASH：B 站给的音轨本身就是完整 m4a，
 *      省掉 buildMediaSource 的解析开销，A53 上首帧更快。
 *   4. 自己在音频管线上插一段 [AudioTap]（见 TapRenderers），频谱与响度均衡都靠它。
 *
 * onEnded / onError 是可变字段而不是构造参数：换歌时要换上绑了新 generation 的闭包，
 * 否则上一首迟到的「播放结束」会把刚起播的新歌推走。
 */
@OptIn(UnstableApi::class)
class AudioPlayer(
    context: Context,
    private val log: (String) -> Unit,
) {

    var onEnded: (() -> Unit)? = null
    var onError: (() -> Unit)? = null

    private val dataSourceFactory =
        OkHttpDataSource.Factory(BiliHttp.client).apply {
            setUserAgent(BiliHttp.UA)
            setDefaultRequestProperties(mapOf("Referer" to BiliHttp.REFERER))
        }

    /** 解码后的 PCM 在这里被分析并做响度均衡，界面通过 [AudioBus] 拿结果 */
    private val tap = AudioTap(log)

    private val exo: ExoPlayer

    init {
        exo = ExoPlayer.Builder(context, TapRenderers(context, tap)).build()
        exo.setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
        AudioBus.tap = tap
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> log("  ⏳ 缓冲中…")
                    Player.STATE_READY -> log("  ▶ 已就绪，时长 ${exo.duration} ms")
                    Player.STATE_ENDED -> {
                        log("  ⏹ 播放结束")
                        onEnded?.invoke()
                    }
                    Player.STATE_IDLE -> log("  ⏸ 空闲")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // 403 / 404 会落在 HttpDataSource 的错误上，这里的 name 比 message 好读
                val cause = generateSequence<Throwable>(error) { it.cause }.last()
                log("  ❌ 播放错误 ${error.errorCodeName} / ${cause.javaClass.simpleName}: ${cause.message}")
                onError?.invoke()
            }
        })
    }

    fun play(url: String, startMs: Long = 0L) {
        val source = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(url))
        exo.setMediaSource(source)
        exo.prepare()
        if (startMs > 0) exo.seekTo(startMs)
        exo.playWhenReady = true
    }

    fun seekTo(ms: Long) {
        exo.seekTo(ms)
    }

    val positionMs: Long get() = exo.currentPosition
    val durationMs: Long get() = if (exo.duration > 0) exo.duration else 0L
    val isPlaying: Boolean get() = exo.isPlaying

    fun pause() {
        exo.pause()
    }

    fun resume() {
        exo.play()
    }

    fun release() {
        exo.release()
    }

    /**
     * 给播放器换一条「带分析器」的音频管线。
     *
     * DefaultRenderersFactory 默认只建一个裸的 DefaultAudioSink；这里把 [tap] 塞进
     * 它的处理器链，PCM 在送进 AudioTrack 之前先经过我们——这是拿到真实采样数据
     * 唯一不依赖权限、也不依赖厂商效果器的办法。
     *
     * 关掉浮点输出：分析器只处理 16 位整型。浮点一开，configure 会返回 NOT_SET
     * 直接旁路，频谱和均衡就都没了。
     */
    @OptIn(UnstableApi::class)
    private class TapRenderers(
        context: Context,
        private val tap: AudioTap,
    ) : DefaultRenderersFactory(context) {

        init {
            setEnableAudioFloatOutput(false)
        }

        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink = DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf(tap))
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }
}
