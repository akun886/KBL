package com.kunplayer.tv.cache

import android.content.Context

/**
 * 播放设置。四项，都是「设一次用很久」的偏好，存在一个 SharedPreferences 里。
 *
 * 为什么在内存里再缓存一份：列表每一行绑定时都要读频谱颜色，一屏二十行就是二十次
 * `getSharedPreferences`。SharedPreferences 本身有内存镜像，但那次调用要拿锁、要查 map，
 * 在一个 60fps 的滑动里不值得。这里缓存的是「已经解析好的值」，写的时候两边一起改。
 */
object Settings {

    private const val PREF = "settings"

    const val STYLE_BARS = 0
    const val STYLE_WAVE = 1
    const val STYLE_RING = 2
    const val STYLE_COUNT = 3

    /**
     * 频谱可选的六种颜色。
     *
     * 全是高明度、高饱和的色：频谱画在封面模糊后的深底上，暗色（深蓝、墨绿）会整块糊进
     * 背景里，看起来像没画。白色留在最后一个——它是「不挑颜色」的那个选项。
     */
    val COLORS = intArrayOf(
        0xFF35D6E8.toInt(), // 青
        0xFF8C7BFF.toInt(), // 紫
        0xFFFFA53D.toInt(), // 橙
        0xFF4ADE80.toInt(), // 绿
        0xFFFF6FA5.toInt(), // 粉
        0xFFF2F2F5.toInt(), // 白
    )

    @Volatile
    private var styleCache = -1

    @Volatile
    private var colorCache = -1

    @Volatile
    private var normalizeCache = -1

    @Volatile
    private var bgPlayCache = -1

    private fun pref(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun style(c: Context): Int {
        val cached = styleCache
        if (cached >= 0) return cached
        val v = pref(c).getInt("spectrum_style", STYLE_BARS).coerceIn(0, STYLE_COUNT - 1)
        styleCache = v
        return v
    }

    fun cycleStyle(c: Context): Int {
        val next = (style(c) + 1) % STYLE_COUNT
        styleCache = next
        pref(c).edit().putInt("spectrum_style", next).apply()
        return next
    }

    /** 颜色下标，不是色值 */
    fun colorIndex(c: Context): Int {
        val cached = colorCache
        if (cached >= 0) return cached
        val v = pref(c).getInt("spectrum_color", 0).coerceIn(0, COLORS.size - 1)
        colorCache = v
        return v
    }

    fun color(c: Context): Int = COLORS[colorIndex(c)]

    fun cycleColor(c: Context): Int {
        val next = (colorIndex(c) + 1) % COLORS.size
        colorCache = next
        pref(c).edit().putInt("spectrum_color", next).apply()
        return next
    }

    /** 响度均衡。默认开——这是「所有歌音量自动拉齐」，关掉才有理由去动它 */
    fun normalize(c: Context): Boolean {
        val cached = normalizeCache
        if (cached >= 0) return cached == 1
        val v = if (pref(c).getBoolean("normalize", true)) 1 else 0
        normalizeCache = v
        return v == 1
    }

    fun setNormalize(c: Context, on: Boolean) {
        normalizeCache = if (on) 1 else 0
        pref(c).edit().putBoolean("normalize", on).apply()
    }

    /**
     * 回桌面后是否继续播放。默认开：这个应用本来就是「电视上听歌」，
     * 按一下 HOME 就断掉是最不像播放器的行为。
     */
    fun backgroundPlay(c: Context): Boolean {
        val cached = bgPlayCache
        if (cached >= 0) return cached == 1
        val v = if (pref(c).getBoolean("background_play", true)) 1 else 0
        bgPlayCache = v
        return v == 1
    }

    fun setBackgroundPlay(c: Context, on: Boolean) {
        bgPlayCache = if (on) 1 else 0
        pref(c).edit().putBoolean("background_play", on).apply()
    }
}
