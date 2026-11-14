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

    // ---------------------------------------------------------------- 歌词

    /** 字号四档，倍数作用在 item_lyric 的基准 22sp 上 */
    const val LYRIC_SIZE_SMALL = 0
    const val LYRIC_SIZE_NORMAL = 1
    const val LYRIC_SIZE_LARGE = 2
    const val LYRIC_SIZE_HUGE = 3
    const val LYRIC_SIZE_COUNT = 4
    val LYRIC_SCALES = floatArrayOf(0.85f, 1f, 1.22f, 1.45f)

    /** 字体三档。电视上只有这三套中文字形是稳的，别的名字在部分机型上会回落到默认 */
    const val LYRIC_FONT_DEFAULT = 0
    const val LYRIC_FONT_HEI = 1
    const val LYRIC_FONT_SONG = 2
    const val LYRIC_FONT_COUNT = 3

    @Volatile
    private var lyricOnCache = -1

    @Volatile
    private var lyricColorCache = -1

    @Volatile
    private var lyricFontCache = -1

    @Volatile
    private var lyricSizeCache = -1

    @Volatile
    private var qualityCache = -1

    // ---------------------------------------------------------------- 音质

    const val QUALITY_AUTO = 0
    const val QUALITY_LOSSLESS = 1
    const val QUALITY_HIGH = 2
    const val QUALITY_NORMAL = 3
    const val QUALITY_LOW = 4
    const val QUALITY_COUNT = 5

    // ---------------------------------------------------------------- 关键词

    /**
     * 首页「推荐音乐」那排快速筛选按钮，十三个槽位。
     *
     * 十三个是量出来的：1920×1080 / 320dpi 下那排可用宽度 1142dp，
     * 一个两字词连内边距约 80dp，十三个刚好铺满一行还留一点余量；
     * 十四个就要换行，而换行之后两行只有两三颗在第二行，比一排更长。
     *
     * 只放默认值：用户改过的词存在 prefs 里，按下标覆盖。改默认表不影响已存的，
     * 想回到出厂状态走设置页的「恢复默认」。
     *
     * 老用户的存值可能是十个词（0.30.0/0.30.1）：读的时候按位补齐到十三个，
     * 补出来的正是第 11～13 项，不需要迁移代码，也不会把老词顶掉。
     */
    val DEFAULT_KEYWORDS = listOf(
        "国语", "粤语", "纯音乐", "抖音", "无损",
        "合集", "演唱会", "经典", "精选", "Hi-Res",
        "分P", "闽南语", "金曲",
    )

    const val KEYWORD_SLOTS = 13

    @Volatile
    private var keywordCache: List<String>? = null

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

    // ---------------------------------------------------------------- 歌词设置

    /**
     * 是否显示歌词。默认开。
     *
     * 关掉不是「不加载」——字幕接口本来就有磁盘缓存，加载那一下不花人任何东西；
     * 关的是「两个页面都不把歌词摆出来」。这样再打开时不用等第二次请求。
     */
    fun lyricsOn(c: Context): Boolean {
        val cached = lyricOnCache
        if (cached >= 0) return cached == 1
        val v = if (pref(c).getBoolean("lyrics_on", true)) 1 else 0
        lyricOnCache = v
        return v == 1
    }

    fun setLyricsOn(c: Context, on: Boolean) {
        lyricOnCache = if (on) 1 else 0
        pref(c).edit().putBoolean("lyrics_on", on).apply()
    }

    /** 歌词颜色，和频谱共用一套十色 */
    fun lyricsColorIndex(c: Context): Int {
        val cached = lyricColorCache
        if (cached >= 0) return cached
        // 默认白：歌词压在封面模糊底上，只有白色在任何一张封面上都读得清
        val v = pref(c).getInt("lyrics_color", 5).coerceIn(0, COLORS.size - 1)
        lyricColorCache = v
        return v
    }

    fun lyricsColor(c: Context): Int = COLORS[lyricsColorIndex(c)]

    fun setLyricsColor(c: Context, index: Int) {
        val v = index.coerceIn(0, COLORS.size - 1)
        lyricColorCache = v
        pref(c).edit().putInt("lyrics_color", v).apply()
    }

    fun lyricsFont(c: Context): Int {
        val cached = lyricFontCache
        if (cached >= 0) return cached
        val v = pref(c).getInt("lyrics_font", LYRIC_FONT_DEFAULT).coerceIn(0, LYRIC_FONT_COUNT - 1)
        lyricFontCache = v
        return v
    }

    /** 循环换字体，返回新档。设置行按一下换一个值，和频谱样式同一种手感 */
    fun cycleLyricsFont(c: Context): Int {
        val next = (lyricsFont(c) + 1) % LYRIC_FONT_COUNT
        lyricFontCache = next
        pref(c).edit().putInt("lyrics_font", next).apply()
        return next
    }

    fun lyricsSize(c: Context): Int {
        val cached = lyricSizeCache
        if (cached >= 0) return cached
        val v = pref(c).getInt("lyrics_size", LYRIC_SIZE_NORMAL).coerceIn(0, LYRIC_SIZE_COUNT - 1)
        lyricSizeCache = v
        return v
    }

    fun cycleLyricsSize(c: Context): Int {
        val next = (lyricsSize(c) + 1) % LYRIC_SIZE_COUNT
        lyricSizeCache = next
        pref(c).edit().putInt("lyrics_size", next).apply()
        return next
    }

    fun lyricsScale(c: Context): Float = LYRIC_SCALES[lyricsSize(c)]

    // ---------------------------------------------------------------- 音质

    /**
     * 0 自动（无损优先，其次最高码率）/ 1 无损 / 2 高品质 / 3 标准 / 4 流畅。
     *
     * 存的是档位不是码率：B 站换过一次音频 id 表，存数字迟早对不上。
     */
    fun quality(c: Context): Int {
        val cached = qualityCache
        if (cached >= 0) return cached
        val v = pref(c).getInt("quality", QUALITY_AUTO).coerceIn(0, QUALITY_COUNT - 1)
        qualityCache = v
        return v
    }

    fun cycleQuality(c: Context): Int {
        val next = (quality(c) + 1) % QUALITY_COUNT
        qualityCache = next
        pref(c).edit().putInt("quality", next).apply()
        return next
    }

    // ---------------------------------------------------------------- 关键词

    /**
     * 十五个筛选词。文件里存一整串（`\n` 分隔），读的时候补齐到十五位。
     *
     * 补齐而不是丢弃：老版本没有这一项、或者哪天从十五个减到六个，
     * 界面上少了按钮比少了词难查得多。空槽回落到默认词。
     */
    fun keywords(c: Context): List<String> {
        keywordCache?.let { return it }
        val raw = pref(c).getString("search_keywords", "").orEmpty()
        val saved = if (raw.isEmpty()) emptyList() else raw.split('\n')
        val out = (0 until KEYWORD_SLOTS).map { i ->
            saved.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_KEYWORDS[i]
        }
        keywordCache = out
        return out
    }

    fun setKeyword(c: Context, index: Int, value: String) {
        if (index !in 0 until KEYWORD_SLOTS) return
        val word = value.trim()
        if (word.isEmpty()) return
        val next = keywords(c).toMutableList()
        next[index] = word
        keywordCache = next
        pref(c).edit().putString("search_keywords", next.joinToString("\n")).apply()
    }

    fun resetKeywords(c: Context) {
        keywordCache = DEFAULT_KEYWORDS
        pref(c).edit().remove("search_keywords").apply()
    }
}
