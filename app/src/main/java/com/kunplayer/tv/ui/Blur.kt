package com.kunplayer.tv.ui

import android.graphics.Bitmap
import android.util.LruCache

/**
 * 播放页的背景模糊。
 *
 * 不用 RenderScript：ScriptIntrinsicBlur 在 API 31 已废弃，而且它要单独的
 * androidx.renderscript 包，为一张背景图不值得。这里在 96×54 上做三轮
 * 箱式模糊（等价于一次接近高斯的模糊），再用双线性放大 5 倍——
 * 相对半径已经很大，放大后是一块干净的光斑，看不出网格。
 *
 * 代价：96×54×6 次遍历约 3 万像素操作，A53 上不到 2ms，可以直接在主线程做。
 *
 * 缓存按 bvid 存放大后的位图（480×270，约 500KB）。只留 4 张：
 * 切歌时来回横跳不会重算，又不会把内存吃掉。
 */
object Blur {

    private const val W = 96
    private const val H = 54
    private const val SCALE = 5
    private const val RADIUS = 2

    private val cache = object : LruCache<String, Bitmap>(4) {}

    fun of(key: String, source: Bitmap): Bitmap? {
        cache.get(key)?.let { return it }

        val small = Bitmap.createScaledBitmap(source, W, H, true)
        val pixels = IntArray(W * H)
        small.getPixels(pixels, 0, W, 0, 0, W, H)
        if (small !== source) small.recycle()

        var a = pixels
        var b = IntArray(W * H)
        repeat(3) {
            blurHorizontal(a, b, W, H, RADIUS)
            blurVertical(b, a, W, H, RADIUS)
        }

        val tiny = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        tiny.setPixels(a, 0, W, 0, 0, W, H)
        val big = Bitmap.createScaledBitmap(tiny, W * SCALE, H * SCALE, true)
        tiny.recycle()

        cache.put(key, big)
        return big
    }

    /** 边缘用「夹紧」而不是补黑：补黑会在四周压出一圈暗边，放大后像一层脏雾 */
    private fun blurHorizontal(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val base = y * w
            var sa = 0
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = src[base + i.coerceIn(0, w - 1)]
                sa += (p ushr 24) and 0xFF
                sr += (p shr 16) and 0xFF
                sg += (p shr 8) and 0xFF
                sb += p and 0xFF
            }
            for (x in 0 until w) {
                dst[base + x] = ((sa / div) shl 24) or ((sr / div) shl 16) or
                    ((sg / div) shl 8) or (sb / div)

                val out = src[base + (x - r).coerceIn(0, w - 1)]
                val into = src[base + (x + r + 1).coerceIn(0, w - 1)]
                sa += ((into ushr 24) and 0xFF) - ((out ushr 24) and 0xFF)
                sr += ((into shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                sg += ((into shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                sb += (into and 0xFF) - (out and 0xFF)
            }
        }
    }

    private fun blurVertical(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        val stride = w
        for (x in 0 until w) {
            var sa = 0
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = src[i.coerceIn(0, h - 1) * stride + x]
                sa += (p ushr 24) and 0xFF
                sr += (p shr 16) and 0xFF
                sg += (p shr 8) and 0xFF
                sb += p and 0xFF
            }
            for (y in 0 until h) {
                dst[y * stride + x] = ((sa / div) shl 24) or ((sr / div) shl 16) or
                    ((sg / div) shl 8) or (sb / div)

                val out = src[(y - r).coerceIn(0, h - 1) * stride + x]
                val into = src[(y + r + 1).coerceIn(0, h - 1) * stride + x]
                sa += ((into ushr 24) and 0xFF) - ((out ushr 24) and 0xFF)
                sr += ((into shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                sg += ((into shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                sb += (into and 0xFF) - (out and 0xFF)
            }
        }
    }
}
