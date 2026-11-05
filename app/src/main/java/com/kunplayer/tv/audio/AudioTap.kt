package com.kunplayer.tv.audio

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 音频分析 + 响度均衡。
 *
 * 为什么插在解码器后面而不是用系统 Visualizer：
 *
 * Visualizer / LoudnessEnhancer 都要 `RECORD_AUDIO` 权限，还要按 audio session 挂效果器。
 * 在一台 2017 年的 Amlogic 电视上这两样都不保证存在，而权限对话框会让一个「听歌」
 * 应用张口要录音，比功能没有更糟。挂在 ExoPlayer 的音频管线上就没有这些问题：
 * PCM 是解码器亲手交给我们的，不经过任何系统效果器。
 *
 * 它同时干三件事，都是同一次遍历：
 *
 * 1. **频谱**——1024 点 FFT，对数分 32 个频带，供 [com.kunplayer.tv.ui.SpectrumView] 画柱状/环形；
 * 2. **波形**——窗口最后 96 个采样点，画示波器；
 * 3. **响度均衡**——每首歌开头测 5 秒长时 RMS，算出**一个固定增益**后锁死，整首不再变。
 *
 * 均衡是「歌与歌之间拉齐」，不是歌内动态压缩。0.29.1 曾用瞬时 RMS 逐块追目标：
 * 每 0.55 秒拿那一块的 RMS 重算一次目标，增益一直追一个随机游走的值；再叠加
 * 逐块硬钳的峰值保护（鼓点一来增益当场砍一半、下一块弹回），听感就是
 * 「音量自己突然变大变小」。现在歌内增益恒定，峰值保护改成慢起更慢落，
 * 锁定时还给天花板留一档峰值余量，让播放期的兜底几乎不必动。
 *
 * 只在 16 位 PCM 上启用（[onConfigure] 里判），其余格式返回 NOT_SET 直接旁路：
 * 浮点输出会让这段增益运算变成另一套量纲，宁可不做也不要算错。
 *
 * 注意线程：queueInput 跑在播放线程，bands/wave 被 UI 线程直接读。float 数组的单个元素
 * 读写是原子的，读到一帧半新半旧的数据最多是画面抖一下，不值得为它加锁。
 */
@OptIn(UnstableApi::class)
class AudioTap(
    /** 诊断日志。频谱「有没有数据」是这一路唯一说不清的地方，落一行日志省得靠猜 */
    private val log: (String) -> Unit = {},
) : BaseAudioProcessor() {

    companion object {
        /** 频带数。32 个在 1080p、三米外刚好能数清，再多就糊成一片 */
        const val BANDS = 32

        /** 波形取多少个点。96 点在 500dp 宽上画出来是连续的 */
        const val WAVE = 96

        /** 1024 点：44.1kHz 下是 23ms 一窗，低频分辨率 43Hz，够分出鼓和贝斯 */
        private const val FFT_N = 1024

        /** 两次 FFT 至少隔这么久。40ms ≈ 25fps，画面上够了，A53 上也还留得住余量 */
        private const val FFT_GAP_MS = 40L

        /** 目标响度：约 -19 dBFS。流行歌的 RMS 大多落在 -22 ~ -12 之间 */
        private const val TARGET_RMS = 0.11f

        /** 增益上下限，折合 -8dB ~ +8dB。再大就是失真，再小等于把人声压没了 */
        private const val MIN_GAIN = 0.4f
        private const val MAX_GAIN = 2.5f

        /** 锁定后向固定增益靠拢的速度：以 20ms 的块为基准，每块 3% */
        private const val GAIN_SLEW = 0.03f

        /** 低于这个 RMS 当成静音，不进长时响度统计——淡入和间奏会把测量拉偏 */
        private const val SILENCE = 0.004f

        /** 测够多少有效音频才锁定。5 秒足以跨过前奏，又不至于让人等太久 */
        private const val MEASURE_MS = 5000f
        private const val MIN_MEASURE_BLOCKS = 100

        /** 测量期每多少个有效块用累积值刷新一次候选。24 块约 0.5 秒 */
        private const val GAIN_WINDOW = 24

        /** 满幅留 1.5% 余量。锁定时的天花板与播放期的兜底共用同一个数 */
        private const val PEAK_LIMIT = 32767f * 0.985f

        /**
         * 测量窗口只覆盖开头，副歌的峰值通常更高。锁定时按 `maxPeak × 1.12`
         * 算天花板：宁可少放大一点，也不要整首靠播放期的兜底一路压着走。
         * 只对「需要放大」的歌起作用，`fixedGain < 1` 的歌不受影响。
         */
        private const val PEAK_MARGIN = 1.12f

        /**
         * 峰值保护的慢起、更慢落。attack 时间常数约 1 秒，鼓点级瞬态根本来不及
         * 拉动 guard；release 约 20 秒，回落慢到听不出「被拉了一把」。
         * 真机上试过 0.25 / 0.003：seek 到高响度段后 guard 在 0.84~0.93 来回摆，
         * 整体增益仍有约 1dB 起伏。超出的瞬态交给逐样本钳位，宁可极短促地削一下，
         * 也不要整体音量跟着音乐抽。
         */
        private const val GUARD_ATTACK = 0.02f
        private const val GUARD_RELEASE = 0.001f
    }

    /** 每帧的频带能量 0..1，从左到右由低到高 */
    val bands = FloatArray(BANDS)

    /** 最近一窗的波形，-1..1 */
    val wave = FloatArray(WAVE)

    /** 当前电平 0..1（已含增益）。环形频谱中心的脉动用它 */
    @Volatile
    var level = 0f
        private set

    /** 最后一次收到 PCM 的时刻（elapsedRealtime）。视图靠它判断数据还新不新鲜 */
    @Volatile
    var updatedAt = 0L
        private set

    private var sampleRate = 44100
    private var channels = 2
    private var edges = IntArray(BANDS + 1)

    private val mono = FloatArray(FFT_N)
    private var monoAt = 0
    private var lastFftAt = 0L

    private val re = FloatArray(FFT_N)
    private val im = FloatArray(FFT_N)
    private val window = FloatArray(FFT_N) { 0.5f - 0.5f * cos(2.0 * Math.PI * it / (FFT_N - 1)).toFloat() }
    private val fft = Fft(FFT_N)

    /** 实际乘到 PCM 上的增益。只在每首歌锁定后过渡一次，之后整首恒定 */
    private var gain = 1f

    /** 锁定后的固定增益。由长时 RMS 算一次，不再重算 */
    private var fixedGain = 1f

    /** 峰值保护系数 0..1，快起慢落乘在 gain 上 */
    private var guard = 1f

    private var locked = false
    private var rmsSqSum = 0.0
    private var validBlocks = 0
    private var measureMs = 0f
    private var maxPeak = 0

    /** 自证：只在整体增益真的动了大步时才落一行日志 */
    private var lastLoggedG = 1f
    private var lastGainLogAt = 0L

    private var loggedFirst = false
    private var loggedGain = false

    // ------------------------------------------------------------ 配置

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            log("频谱旁路：编码 ${inputAudioFormat.encoding} 不是 16 位 PCM")
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        edges = bandEdges(sampleRate)
        monoAt = 0
        log("频谱就绪 ${sampleRate}Hz ${channels}ch / $BANDS 频带")
        return AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT)
    }

    /**
     * 频带边界（FFT bin 下标）。
     *
     * 对数分布 40Hz ~ 16kHz：音乐的细节集中在低频，等宽分带会把整块低频挤进前两个格子，
     * 画面左边两根柱子狂跳、右边一动不动。
     */
    private fun bandEdges(rate: Int): IntArray {
        val out = IntArray(BANDS + 1)
        val maxBin = FFT_N / 2 - 1
        for (i in 0..BANDS) {
            val f = 40.0 * Math.pow(16000.0 / 40.0, i.toDouble() / BANDS)
            val bin = Math.round(f * FFT_N / rate).toInt().coerceIn(1, maxBin)
            out[i] = if (i == 0) 1 else maxOf(bin, out[i - 1] + 1).coerceAtMost(maxBin)
        }
        return out
    }

    override fun onFlush() {
        // 只清 FFT 窗口。seek / 缓冲 / 暂停恢复都走到这里，增益不能跟着重置，
        // 否则拖一次进度条就要重测响度、音量会变。换歌由 newTrack() 显式重置。
        monoAt = 0
        lastFftAt = 0L
    }

    /** 换歌时调：重新测这一首的响度。由 [com.kunplayer.tv.player.AudioPlayer.play] 驱动 */
    fun newTrack() {
        onFlush()
        resetMeasure()
    }

    private fun resetMeasure() {
        gain = 1f
        fixedGain = 1f
        guard = 1f
        locked = false
        rmsSqSum = 0.0
        validBlocks = 0
        measureMs = 0f
        maxPeak = 0
    }

    override fun onReset() {
        onFlush()
        bands.fill(0f)
        wave.fill(0f)
        level = 0f
        resetMeasure()
        updatedAt = 0L
    }

    // ------------------------------------------------------------ 逐块处理

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size < 2) {
            inputBuffer.position(inputBuffer.limit())
            return
        }
        val out = replaceOutputBuffer(size)
        val src = inputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val dst = out.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val n = size / 2
        val frames = n / channels

        // 先量这一块，再决定这一块乘多少。量的是「进来时」的响度，
        // 固定增益才是「总共该乘多少」；拿放大后的样本去算，会自己追自己
        var sum = 0.0
        var peak = 0
        for (i in 0 until n) {
            val v = src.get(i).toInt()
            sum += (v * v).toLong()
            val a = if (v < 0) -v else v
            if (a > peak) peak = a
        }
        val rms = (sqrt(sum / n) / 32768.0).toFloat()
        val now = SystemClock.elapsedRealtime()
        val blockMs = frames * 1000f / sampleRate
        val normalize = AudioBus.normalize
        updateGain(rms, peak, blockMs, normalize)

        val g = if (normalize) gain * guard else 1f

        var idx = 0
        while (idx < n) {
            // 末块可能不是整帧：count 取剩余数，别让 dst 尾部留着上一块的脏数据
            val count = minOf(channels, n - idx)
            var acc = 0
            for (c in 0 until count) {
                var v = (src.get(idx) * g).toInt()
                if (v > 32767) v = 32767 else if (v < -32768) v = -32768
                dst.put(idx, v.toShort())
                acc += v
                idx++
            }
            if (monoAt < FFT_N) {
                mono[monoAt++] = acc.toFloat() / count / 32768f
                if (monoAt == FFT_N) {
                    analyze(now)
                    monoAt = 0
                }
            }
        }
        inputBuffer.position(inputBuffer.limit())

        level = ((20f * log10(rms * g + 1e-5f) + 48f) / 48f).coerceIn(0f, 1f)
        updatedAt = now
    }

    /**
     * 每首歌只定一次目标：前 [MEASURE_MS] 毫秒累积长时 RMS，测够就锁死。
     *
     * 测量期每 0.5 秒用**累积**长时 RMS 刷一次候选（不是单块瞬时值，所以候选是
     * 单调收敛的），gain 平滑跟着走；锁定后 `fixedGain` 再也不变，一首歌里
     * 音量只在开头过渡一次，播起来之后恒定。`rms` 一律取放大前的原始 PCM。
     */
    private fun updateGain(rms: Float, peak: Int, blockMs: Float, normalize: Boolean) {
        if (!normalize) {
            // 关掉时持续清状态，重新打开就会重新测，不会捡起关之前的半截测量
            resetMeasure()
            return
        }

        if (!locked && rms >= SILENCE) {
            rmsSqSum += rms.toDouble() * rms
            validBlocks++
            measureMs += blockMs
            if (peak > maxPeak) maxPeak = peak
            val done = validBlocks >= MIN_MEASURE_BLOCKS && measureMs >= MEASURE_MS
            if (validBlocks % GAIN_WINDOW == 0 || done) {
                val longRms = sqrt(rmsSqSum / validBlocks).toFloat()
                // 天花板按测量窗口的最大峰值再加一档余量：一次算好，播放期兜底几乎不触发
                val ceiling = PEAK_LIMIT / (maxPeak.coerceAtLeast(1) * PEAK_MARGIN)
                fixedGain = (TARGET_RMS / longRms)
                    .coerceIn(MIN_GAIN, MAX_GAIN)
                    .coerceAtMost(ceiling)
                if (done) {
                    locked = true
                    // 只落一次「这首歌测出来要乘多少」，确认均衡真的在工作
                    if (!loggedGain) {
                        loggedGain = true
                        log("响度均衡 锁定 ×%.2f（长时 RMS %.3f）".format(fixedGain, longRms))
                    }
                }
            }
        }

        // 按块时长折算，20ms 的块正好挪 3%；块大小不固定时过渡时间才不会忽快忽慢
        val a = (1f - Math.pow(
            1.0 - GAIN_SLEW.toDouble(),
            (blockMs / 20f).toDouble(),
        ).toFloat()).coerceIn(GAIN_SLEW, 0.25f)
        gain += (fixedGain - gain) * a

        // 峰值保护：guard 是「要让 peak × gain 不削波，得把增益乘多少」。
        // 慢起更慢落，鼓点级瞬态拉不动它。0.29.1 逐块硬钳 room，鼓点每来一下
        // 音量就掉一截再弹回，那是「突然变大变小」最直接的来源。
        val need = (PEAK_LIMIT / (peak * gain + 1f)).coerceAtMost(1f)
        guard += if (need < guard) {
            (need - guard) * GUARD_ATTACK
        } else {
            (1f - guard) * GUARD_RELEASE
        }

        // 只在实际音量动了大步时留一行。锁定后这里应当安静——
        // 万一哪天又有人说「音量自己变」，这几行就是第一手证据。
        val g = gain * guard
        val now = SystemClock.elapsedRealtime()
        if (abs(g - lastLoggedG) >= 0.02f && now - lastGainLogAt >= 1000L) {
            lastGainLogAt = now
            lastLoggedG = g
            log("响度 变化 ×%.2f（目标 %.2f / 峰值保护 %.2f）".format(g, fixedGain, guard))
        }
    }

    // ------------------------------------------------------------ 分析

    private fun analyze(now: Long) {
        // 窗口满了但离上一次分析太近：这一窗样本直接丢掉。宁可少一帧，
        // 也不要让 FFT 在播放线程上排队——那边一卡，声音就断续
        if (now - lastFftAt < FFT_GAP_MS) return
        lastFftAt = now

        for (i in 0 until FFT_N) {
            re[i] = mono[i] * window[i]
            im[i] = 0f
        }
        fft.transform(re, im)

        val norm = 2f / FFT_N
        for (b in 0 until BANDS) {
            var peak = 0f
            val from = edges[b]
            val to = edges[b + 1]
            for (k in from until to) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k]) * norm
                if (mag > peak) peak = mag
            }
            // -62dB 到 0dB 映射成 0..1。低处留 62dB 是给弦乐、气声这类弱细节的，
            // 只取 -40dB 以上的话，安静段落整排柱子会一起趴下
            val db = 20f * log10(peak + 1e-5f)
            var v = ((db + 62f) / 62f).coerceIn(0f, 1f)
            // 高频能量天然比低频低十几个 dB，不补一点，右半边永远是平的
            v = (v * (1f + b * 0.010f)).coerceAtMost(1f)
            // 快起慢落：鼓点要跳得起来，退的时候要有一点余韵，否则整排柱子像在闪
            bands[b] = if (v > bands[b]) v else bands[b] * 0.86f
        }

        val step = FFT_N / WAVE
        for (i in 0 until WAVE) wave[i] = mono[i * step]

        if (!loggedFirst) {
            loggedFirst = true
            var peakBand = 0f
            for (v in bands) if (v > peakBand) peakBand = v
            log("频谱首帧就绪，最强频带 %.2f".format(peakBand))
        }
    }

    /**
     * 原地 radix-2 FFT。
     *
     * 自己写而不是引依赖：只需要一个固定 1024 点的正变换，外面那些库要么带 JNI，
     * 要么为通用性牺牲速度。旋转因子与位反转表在构造时算好，每帧只做蝶形运算。
     */
    private class Fft(private val n: Int) {
        private val cosT = FloatArray(n / 2)
        private val sinT = FloatArray(n / 2)
        private val rev = IntArray(n)

        init {
            for (i in 0 until n / 2) {
                val a = -2.0 * Math.PI * i / n
                cosT[i] = cos(a).toFloat()
                sinT[i] = sin(a).toFloat()
            }
            var bits = 0
            while ((1 shl bits) < n) bits++
            for (i in 0 until n) {
                var r = 0
                for (b in 0 until bits) {
                    if ((i shr b) and 1 == 1) r = r or (1 shl (bits - 1 - b))
                }
                rev[i] = r
            }
        }

        fun transform(re: FloatArray, im: FloatArray) {
            for (i in 0 until n) {
                val j = rev[i]
                if (j > i) {
                    val tr = re[i]; re[i] = re[j]; re[j] = tr
                    val ti = im[i]; im[i] = im[j]; im[j] = ti
                }
            }
            var len = 2
            while (len <= n) {
                val half = len / 2
                val step = n / len
                var i = 0
                while (i < n) {
                    var k = 0
                    while (k < half) {
                        val c = cosT[k * step]
                        val s = sinT[k * step]
                        val a = i + k
                        val b = a + half
                        val tr = re[b] * c - im[b] * s
                        val ti = re[b] * s + im[b] * c
                        re[b] = re[a] - tr
                        im[b] = im[a] - ti
                        re[a] += tr
                        im[a] += ti
                        k++
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}

/**
 * 音频数据的进程内总线。
 *
 * 播放器与界面之间只传这三样：一块可读的实时频谱、一个「在放没有」的标记、
 * 一个响度均衡开关。用单例而不是往每个 View 里传引用，是因为播放页、首页底栏、
 * 列表行三处都要读同一份数据，而它们的构造时机各不相同。
 */
object AudioBus {
    /** 解码管线里的分析器。播放器建好就有，进程内不变 */
    @Volatile
    var tap: AudioTap? = null

    /** 当前是否在出声。迷你频谱靠它决定动不动 */
    @Volatile
    var playing: Boolean = false

    /** 响度均衡开关，默认开 */
    @Volatile
    var normalize: Boolean = true
}
