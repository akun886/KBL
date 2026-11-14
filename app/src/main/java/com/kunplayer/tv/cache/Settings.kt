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
     * 频谱可选的十种颜色，色板按这个顺序铺。
     *
     * 全是高明度、高饱和的色：频谱画在封面模糊后的深底上，暗色（深蓝、墨绿）会整块糊进
     * 背景里，看起来像没画。后三个是中性色——白色是「不挑颜色」的那一档，
     * 两级灰给不想让频谱抢封面的人。
     *
     * **前六个的下标不能动。** 存的是下标不是色值，重排等于把老用户已经选好的颜色
     * 换成另一种。新色一律追加在末尾。
     */
    val COLORS = intArrayOf(
        0xFF35D6E8.toInt(), // 青
        0xFF8C7BFF.toInt(), // 紫
        0xFFFFA53D.toInt(), // 橙
        0xFF4ADE80.toInt(), // 绿
        0xFFFF6FA5.toInt(), // 粉
        0xFFF2F2F5.toInt(), // 白
        0xFFFA233B.toInt(), // 品牌红（与 accent 同值）
        0xFF4D8DFF.toInt(), // 蓝
        0xFFC6C6CC.toInt(), // 浅灰
        0xFF8A8A90.toInt(), // 中灰
    )

    @Volatile
    private var styleCache = -1

    @Volatile
    private var colorCache = -1

    @Volatile
    private var normalizeCache = -1

    @Volatile
    private var bgPlayCache = -1

    @Volatile
    private var barPaneCache = Int.MIN_VALUE

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

    /** 色板上直接点一格。越界的下标一律夹住，不让坏值写进偏好 */
    fun setColor(c: Context, index: Int) {
        val v = index.coerceIn(0, COLORS.size - 1)
        colorCache = v
        pref(c).edit().putInt("spectrum_color", v).apply()
    }

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

    /**
     * 首页底栏那一格显示什么。三档：歌词 / 小频谱 / 沉浸（占满两格的大频谱）。
     *
     * 存的是「用户显式选的那一档」，-1 表示自动档：有词给词、没词给频谱。
     * 和播放页的 `userPicked` 同理——跨页面、跨进程都该停在上次选的那档，
     * 不能因为进了一次播放页就被打回自动档。
     */
    fun barPane(c: Context): Int {
        val cached = barPaneCache
        if (cached != Int.MIN_VALUE) return cached
        val v = pref(c).getInt("bar_pane", -1).coerceIn(-1, 2)
        barPaneCache = v
        return v
    }

    fun setBarPane(c: Context, value: Int) {
        val v = value.coerceIn(-1, 2)
        barPaneCache = v
        pref(c).edit().putInt("bar_pane", v).apply()
    }
}
