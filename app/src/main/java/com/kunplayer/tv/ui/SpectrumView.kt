package com.kunplayer.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.kunplayer.tv.audio.AudioBus
import com.kunplayer.tv.audio.AudioTap
import com.kunplayer.tv.cache.Settings
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 频谱。同一块控件画四种东西，靠 [Mode] 分：
 *
 *   · BARS  柱状——32 根柱子跟着真实频带跳，播放页大图与首页底栏都用它；
 *   · WAVE  波形——示波器式的折线，取最近 23ms 的采样；
 *   · RING  环形——频带绕成一圈朝外发散，中间一颗随电平呼吸的点；
 *   · MINI  迷你——5 根小柱子，**不接音频**，只用正弦相位做循环动画。
 *
 * MINI 是唯一「假动」的一种：列表里一屏只有一行是正在播的，它要回答的是
 * 「就是这首」而不是「此刻这个鼓点」，跟着音乐跳反而会在一列静止的歌词里
 * 抢注意力。播放页那张大图才需要是真的。
 *
 * 数据从 [AudioBus] 拿，不持有播放器：播放页、底栏、列表行三处的构造时机各不相同，
 * 传引用要改一长串构造函数，而它们读的本来就是同一份数据。
 *
 * 自绘 + `postOnAnimationDelayed`，不用属性动画：一张大图上要同时动 32 个数值，
 * 32 个 ObjectAnimator 在 A53 上光是调度开销就够呛，自己算一帧更便宜。
 */
class SpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Mode { BARS, WAVE, RING, MINI }

    var mode: Mode = Mode.BARS
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    var tint: Int = Settings.color(context)
        set(value) {
            if (field == value) return
            field = value
            paint.color = value
            invalidate()
        }

    /** 暂停时迷你频谱停手：一首没在响的歌旁边跳着柱子，是在骗人 */
    var playing: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) start() else stop()
        }

    private companion object {
        /** 30fps。电视上 60fps 的频谱和 30fps 看不出差别，但 CPU 差一倍 */
        const val FRAME_MS = 33L

        /** 迷你柱子更慢一点，它只是「在播」的标记，不该比大图还吵 */
        const val MINI_FRAME_MS = 45L

        const val MINI_BARS = 5

        /** 数据超过这么久没更新就算停了（切歌、暂停、缓冲） */
        const val STALE_MS = 700L
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        style = Paint.Style.FILL
    }

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        style = Paint.Style.STROKE
        strokeWidth = dp(7f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        alpha = 46
    }

    private val rect = RectF()
    private val path = Path()

    private val bars = FloatArray(AudioTap.BANDS)

    /**
     * 迷你柱子的初值。列表里那行是「当前这首」的标记，暂停时动画停手——
     * 但数组是空的就会塌成五个贴地的小点，看着像省略号。给一副静态的等化器形状，
     * 停着的时候至少还认得出这是个频谱。
     */
    private val mini = floatArrayOf(0.42f, 0.86f, 0.58f, 1f, 0.34f)

    private var running = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            if (mode == Mode.MINI) {
                if (playing) stepMini(now)
            } else {
                val tap = AudioBus.tap
                val fresh = tap != null && now - tap.updatedAt < STALE_MS
                stepBars(if (fresh) tap!!.bands else null)
            }
            invalidate()
            postOnAnimationDelayed(this, if (mode == Mode.MINI) MINI_FRAME_MS else FRAME_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 颜色是全局设置，每次上屏都取一次：改完设置不必重建整棵视图树
        tint = Settings.color(context)
        if (mode != Mode.MINI) {
            mode = when (Settings.style(context)) {
                Settings.STYLE_WAVE -> Mode.WAVE
                Settings.STYLE_RING -> Mode.RING
                else -> Mode.BARS
            }
        }
        if (visibility == VISIBLE) start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) start() else stop()
    }

    /** 设置改过之后重取一次样式与颜色。大图与小图都走这里 */
    fun applySettings() {
        tint = Settings.color(context)
        if (mode != Mode.MINI) {
            mode = when (Settings.style(context)) {
                Settings.STYLE_WAVE -> Mode.WAVE
                Settings.STYLE_RING -> Mode.RING
                else -> Mode.BARS
            }
        }
        invalidate()
    }

    private fun start() {
        // isShown 而不是 visibility：底下的播放页、藏起来的浮层里那些视图自己的
        // visibility 仍是 VISIBLE，照 visibility 判会让它们一直空转
        if (running || !isShown || !isAttachedToWindow) return
        running = true
        postOnAnimation(ticker)
    }

    private fun stop() {
        running = false
        removeCallbacks(ticker)
    }

    // ------------------------------------------------------------ 取数

    private fun stepBars(src: FloatArray?) {
        // 暂停之后 AudioTap 不再刷新，柱子会一帧帧退回地板，最后变成一排小点。
        // 停着的是画面，不是数据：冻在最后一帧比塌下去诚实
        if (src == null && !playing) return
        for (i in 0 until AudioTap.BANDS) {
            val target = src?.get(i) ?: 0f
            val cur = bars[i]
            // 起得快、落得慢：鼓点要看得见，退的时候留一条尾巴，
            // 否则 30fps 下整排柱子像在闪
            bars[i] = if (target > cur) cur + (target - cur) * 0.6f else cur * 0.88f
        }
    }

    private fun stepMini(now: Long) {
        val t = now / 1000.0
        for (i in 0 until MINI_BARS) {
            // 每根柱子错开相位，看起来像一条从左往右扫过的波
            val p = t * 2.6 + i * 0.55
            val wave = (sin(p) + sin(p * 2.3 + 1.1) * 0.4) / 1.4
            mini[i] = (0.34f + 0.66f * abs(wave).toFloat()).coerceIn(0f, 1f)
        }
    }

    /** 柱状图取样：宽度不够时把相邻频带并起来，不能画 32 根 0.5px 的线 */
    private fun bandAt(i: Int, n: Int): Float {
        if (n >= AudioTap.BANDS) return bars[i.coerceIn(0, AudioTap.BANDS - 1)]
        val from = i * AudioTap.BANDS / n
        val to = maxOf(from + 1, (i + 1) * AudioTap.BANDS / n)
        var peak = 0f
        for (k in from until min(to, AudioTap.BANDS)) if (bars[k] > peak) peak = bars[k]
        return peak
    }

    // ------------------------------------------------------------ 绘制

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        // 首页底栏那条只有 14dp 高（宽度按「上一首」位置算，见 HomeStage.sizeBarSpectrum）：
        // 环形要一块方形的地才画得出圈，塞进去只剩一个橙点；波形 96 个采样摊在
        // 一条扁带里是一道糊。窄条上一律画柱状——三种样式是给播放页那块大图的。
        val narrow = height < dp(28f)
        when (mode) {
            Mode.MINI -> drawBars(canvas, MINI_BARS, mini)
            Mode.BARS -> drawBars(canvas, barsFor(), null)
            Mode.WAVE -> if (narrow) drawBars(canvas, barsFor(), null) else drawWave(canvas)
            Mode.RING -> if (narrow) drawBars(canvas, barsFor(), null) else drawRing(canvas)
        }
    }

    /** 宽度够就画满 32 根，不够就按 7dp 一根减下来，但不能少于 6 根 */
    private fun barsFor(): Int =
        min(AudioTap.BANDS, (width / dp(7f)).toInt().coerceAtLeast(6))

    private fun drawBars(canvas: Canvas, n: Int, values: FloatArray?) {
        val slot = width.toFloat() / n
        // 柱宽按槽宽算，不设死上限：播放页那张图有 960dp 宽，32 根柱子每根 30dp 的槽，
        // 从前卡在 9dp 上限只铺满左侧三分之一，右边一大片空白。
        // 0.52 是留白与厚度的折中：再宽一点 32 根就连成一块实心板了
        val bw = (slot * 0.52f).coerceIn(dp(1.2f), dp(16f))
        val baseline = height.toFloat()
        val minH = dp(2f)
        paint.style = Paint.Style.FILL
        for (i in 0 until n) {
            val v = values?.get(i) ?: bandAt(i, n)
            val h = minH + (baseline - minH) * v
            val left = i * slot + (slot - bw) / 2f
            rect.set(left, baseline - h, left + bw, baseline)
            // 透明度跟着幅度走：安静段落里柱子退成一层薄影，不会像一排实心积木
            paint.alpha = (110 + 145 * v).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(rect, bw / 2f, bw / 2f, paint)
        }
        paint.alpha = 255
    }

    private fun drawWave(canvas: Canvas) {
        val tap = AudioBus.tap
        val fresh = tap != null && SystemClock.elapsedRealtime() - tap.updatedAt < STALE_MS
        val mid = height / 2f
        val amp = height * 0.42f
        path.reset()
        if (tap == null || !fresh) {
            // 没有数据时画一条静止的中线：完全空白会被读成「控件没加载出来」
            path.moveTo(0f, mid)
            path.lineTo(width.toFloat(), mid)
        } else {
            val w = tap.wave
            for (i in w.indices) {
                val x = width.toFloat() * i / (w.size - 1)
                val y = mid - w[i].coerceIn(-1f, 1f) * amp
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }
        glow.color = tint
        line.color = tint
        canvas.drawPath(path, glow)
        canvas.drawPath(path, line)
    }

    private fun drawRing(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) * 0.26f
        val inner = r + dp(4f)

        // 内圈：一条极淡的圆环，给放射线一个「长在什么上面」的依据
        line.strokeWidth = dp(1.5f)
        line.color = tint
        line.alpha = 52
        canvas.drawCircle(cx, cy, r, line)
        line.alpha = 255

        line.strokeWidth = dp(3f)
        for (i in 0 until AudioTap.BANDS) {
            val v = bars[i]
            val a = (i.toFloat() / AudioTap.BANDS) * (2 * Math.PI) - Math.PI / 2
            val len = (0.05f + v * 0.42f) * r
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            line.alpha = (110 + 145 * v).toInt().coerceIn(0, 255)
            canvas.drawLine(
                cx + c * inner, cy + s * inner,
                cx + c * (inner + len), cy + s * (inner + len),
                line,
            )
        }
        line.alpha = 255

        // 中心那颗：随整体电平呼吸，是这一页唯一会「呼吸」的东西
        val tap = AudioBus.tap
        val lv = if (tap != null && SystemClock.elapsedRealtime() - tap.updatedAt < STALE_MS) {
            tap.level
        } else {
            0f
        }
        paint.style = Paint.Style.FILL
        paint.alpha = 255
        canvas.drawCircle(cx, cy, dp(3f) + dp(6f) * lv, paint)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
