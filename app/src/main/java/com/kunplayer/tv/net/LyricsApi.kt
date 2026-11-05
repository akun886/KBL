package com.kunplayer.tv.net

import com.kunplayer.tv.model.Track
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 歌词：把 B 站的 CC 字幕直接当歌词用。
 *
 * 三步（和 biu 的取数链路一样）：
 *   1. `x/player/wbi/v2` 拿 `data.subtitle.subtitles[]`
 *   2. 取中文那条的 `subtitle_url`（`//` 开头，补 https）——字幕文件是公开 CDN，不带 Cookie
 *   3. 读 `body[{from, content}]`，`from` 是**秒**，两位小数
 *
 * 三个必须记住的事实，都是真机上量出来的：
 *
 * 1. **必须登录。** 匿名请求这个接口返回 `code=0` 但 `subtitles` 是空数组——
 *    又一个「谎报的空列表」，和分区推荐流的 62013 同类，排查时别往 cid 上想。
 * 2. **字幕 URL 只有约 30 秒有效期**（`auth_key` 首段就是过期时间戳）。
 *    所以只缓存解析后的文本，URL 一次都不留；每次播放重新问一次 player 接口。
 * 3. **字幕分两种，别用同一把尺子。**
 *    - `ai-*`：AI 把整段语音都转出来，唱的被 ♪ 包住、说的不包，按 ♪ 筛；
 *      唱的不足 [MIN_SUNG] 行就整份丢弃（宁可留白，不上满屏解说）。
 *    - 人工上传（`zh-Hans` 等）：UP 主自己一句一句打的词，**整份都没有 ♪**，
 *      要求 ♪ 等于把整份丢掉。实测《阳光彩虹小白马》41 行、0 个 ♪。
 *      这种整份采用，下限 [MIN_HUMAN]。
 *
 * 覆盖率本身不乐观：音乐区推荐流 73%、流行榜 40%（2026-11-01 各抽 30 条）。
 * 所以这条链路是「有就显示」，不是「必须有」。
 */
object LyricsApi {

    /** 一行歌词。时间轴与播放器共用同一个 cid，不需要偏移对齐 */
    data class Line(val startMs: Long, val text: String)

    /** 一页歌词的结局：拿到，或者没拿到、但知道为什么 */
    sealed class Outcome {
        data class Ok(val lines: List<Line>) : Outcome()
        data class No(val why: Why) : Outcome()
    }

    /**
     * 没拿到歌词的四种原因。
     *
     * 分开是因为它们对用户的意义完全不同：前两种是「这首歌就这样」，
     * 后两种是「等一下/登录一下就有了」。一律写成「暂无歌词」，
     * 用户只会以为应用坏了，然后再问一次。
     */
    enum class Why {
        /** 接口明确说这个 cid 没有字幕——内容侧没有，应用无能为力 */
        NO_SUBTITLE,

        /** 有字幕，但按判据不像歌词（AI 把解说也转出来了，或者逐字切碎） */
        NOT_LYRIC,

        /** 网络或接口失败，重来一次可能就有 */
        NETWORK,

        /** 连 cid 都反查不到，等于不知道在放哪一条视频 */
        NO_CID,
    }

    private const val BASE = "https://api.bilibili.com"

    /** 少于这么多行「唱」的，说明这份 AI 字幕基本是解说，不值得占一屏 */
    private const val MIN_SUNG = 8

    /**
     * 人工字幕（lan 不是 ai-*）的下限，比 [MIN_SUNG] 松得多。
     *
     * 人工字幕是 UP 主自己一句一句打上去的，整份都是词，没有「说的」和「唱的」之分。
     * 一首短的翻唱可能只有十几行，用 8 去卡会误杀——实测《阳光彩虹小白马》
     * 41 行、0 个 ♪，旧规则整份丢掉，用户看到的就是「有歌词但不显示」。
     */
    private const val MIN_HUMAN = 4

    /**
     * 平均每行至少隔这么久，才算「歌词」。
     *
     * 只拦一种东西：**逐字切碎**。AI 对纯音乐、环境音偶尔会几帧切一行，
     * 那种字幕一行一两个字，密度在 0.1~0.3 秒。真歌词最快也在 1 秒一句以上
     * （老歌 CD 5.8 秒/行、周杰伦 3.7 秒/行），500ms 是留了余量的下限。
     *
     * 拦不住的是另一类：AI 对整张纯音乐专辑「听」出一套假歌词，
     * 稀疏、均匀、去掉符号后和真歌词的结构指标几乎一样（行距 4.8s vs 5.8s、
     * 平均 7.6 字 vs 7.9 字、唯一行 57% vs 61%）——统计上区分不出来，
     * 只能靠用户长按切回封面。不要为它加更狠的规则，那会误杀英文歌。
     */
    private const val MIN_LINE_GAP_MS = 500L

    /** 缓存文件上限。一首一首攒下去，最后会占满 files 目录 */
    private const val CACHE_MAX = 120

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private var dir: File? = null

    fun init(filesDir: File) {
        dir = File(filesDir, "lyrics").apply { mkdirs() }
    }

    /**
     * 拿一首歌的歌词。**必须在 IO 线程调用**，网络与磁盘都在里面。
     *
     * 命中缓存就直接返回，不再打接口——同一首歌反复切来切去是常态。
     * 拿不到时带原因返回：空态要能分清「B 站没给字幕」和「字幕有、但不像是歌词」，
     * 两种都写「暂无歌词」会让人以为应用坏了。
     */
    fun load(track: Track, log: (String) -> Unit): Outcome {
        val cid = track.cid.takeIf { it > 0 } ?: resolveCid(track.bvid, log)
        if (cid <= 0) {
            log("拿不到 cid，无法取字幕")
            return Outcome.No(Why.NO_CID)
        }
        read(cid)?.let { return Outcome.Ok(it) }

        // null 表示两个接口都失败；空列表表示接口明确回了「没有字幕」
        val subs = lookUp(track.bvid, cid, log) ?: return Outcome.No(Why.NETWORK)
        if (subs.isEmpty()) {
            log("$cid 没有字幕（wbi 与明文都空）")
            return Outcome.No(Why.NO_SUBTITLE)
        }
        // 中文优先：其余几条是 AI 翻译，翻出来的歌词不如原句可读
        val pick = subs.firstOrNull { it.lan.contains("zh") } ?: subs.first()
        val url = pick.url.ifEmpty { pick.urlV2 }
        if (url.isEmpty()) {
            log("字幕条目不完整：${pick.lan}")
            return Outcome.No(Why.NO_SUBTITLE)
        }
        val r = BiliHttp.get(if (url.startsWith("//")) "https:$url" else url, withCookie = false)
        if (!r.isOk) {
            log("字幕 HTTP ${r.code}")
            return Outcome.No(Why.NETWORK)
        }
        val doc = runCatching { json.decodeFromString<SubtitleDoc>(r.body) }.getOrNull()
        if (doc == null) {
            log("字幕解析失败：${r.preview}")
            return Outcome.No(Why.NETWORK)
        }

        // 人工上传的字幕（lan 不是 ai-*）是 UP 主自己打上去的歌词，整份都是词，
        // 不能拿 ♪ 去筛——实测《阳光彩虹小白马》41 行，一个 ♪ 都没有。
        // ♪ 是 AI 字幕把「唱的」和「说的」分开的唯一记号，只对 ai 字幕有意义。
        val ai = pick.lan.startsWith("ai-")
        val lines = doc.body
            .mapNotNull { cue -> cue.toLine(requireMark = ai) }
            .sortedBy { it.startMs }
        val need = if (ai) MIN_SUNG else MIN_HUMAN
        if (lines.size < need) {
            log("字幕 ${doc.body.size} 行（lan=${pick.lan}），可用 ${lines.size} 行，丢弃")
            return Outcome.No(Why.NOT_LYRIC)
        }
        // 行距闸门放在条数之后：条数管「有没有唱的」，行距管「这份是不是歌词」
        val span = lines.last().startMs - lines.first().startMs
        if (span / lines.size < MIN_LINE_GAP_MS) {
            log("字幕 ${lines.size} 行挤在 ${span / 1000} 秒里，判为误识别，丢弃")
            return Outcome.No(Why.NOT_LYRIC)
        }
        write(cid, lines)
        log("字幕 ${lines.size} 行（原 ${doc.body.size} · ${pick.lan}）")
        return Outcome.Ok(lines)
    }

    /**
     * 补 cid。收藏夹和搜索结果只给 bvid，cid 是 0——播放器取流时自己反查过一次，
     * 但没有写回队列，于是歌词这边看到的就是 0，整条链路直接跳过，
     * 表现出来是「右栏连『暂无歌词』都不写，一片空白」。
     */
    private fun resolveCid(bvid: String, log: (String) -> Unit): Long {
        if (bvid.isEmpty()) return 0L
        log("cid 缺失，按 bvid 反查 $bvid")
        return runCatching { BiliApi.fetchView(bvid, log).cid }.getOrDefault(0L)
    }

    // ------------------------------------------------------------ 取 url

    /**
     * 问一遍字幕列表。
     *
     * **先 wbi 签名，空了再问明文。** 实测同一个 cid，wbi 接口给 0 条、明文接口给 1 条
     * `ai-zh`（2026-11-02，BV1sF4c6jEWf 的 P1 与 P8）。两条路看到的是不同的字幕集合，
     * 不能互换，只能串起来问；多花一个请求，换回一批本来会被判成「没有字幕」的歌。
     *
     * @return null = 两个接口都请求失败；空列表 = 至少一个接口明确回了「没有字幕」
     */
    private fun lookUp(bvid: String, cid: Long, log: (String) -> Unit): List<SubtitleItem>? {
        // WBI 没就绪时先取一次 nav。签名接口是硬要求，不像推荐流能退回明文
        if (!Wbi.ready()) runCatching { BiliApi.fetchNav(log) }
        val signed = askPlayer(bvid, cid, wbi = true, log)
        if (!signed.isNullOrEmpty()) return signed

        val plain = askPlayer(bvid, cid, wbi = false, log)
        if (!plain.isNullOrEmpty()) {
            log("wbi 空，明文接口拿到 ${plain.size} 条")
            return plain
        }
        return if (signed == null && plain == null) null else emptyList()
    }

    /** null = 请求失败；空列表 = 接口明确说没有 */
    private fun askPlayer(
        bvid: String,
        cid: Long,
        wbi: Boolean,
        log: (String) -> Unit,
    ): List<SubtitleItem>? {
        val params = linkedMapOf("bvid" to bvid, "cid" to cid.toString())
        val query = if (wbi && Wbi.ready()) {
            Wbi.sign(params)
        } else {
            params.entries.joinToString("&") { "${it.key}=${it.value}" }
        }
        val path = if (wbi) "x/player/wbi/v2" else "x/player/v2"
        val r = BiliHttp.get("$BASE/$path?$query")
        if (!r.isOk) {
            log("$path HTTP ${r.code}")
            return null
        }
        val resp = runCatching { json.decodeFromString<PlayerResp>(r.body) }.getOrNull()
        if (resp == null || resp.code != 0) {
            log("$path code=${resp?.code} ${resp?.message}")
            return null
        }
        return resp.data?.subtitle?.subtitles.orEmpty()
    }

    // ------------------------------------------------------------ 缓存

    private fun cacheFile(cid: Long): File? = dir?.let { File(it, "$cid.txt") }

    private fun read(cid: Long): List<Line>? {
        val f = cacheFile(cid) ?: return null
        if (!f.exists()) return null
        return runCatching {
            f.readLines().mapNotNull { row ->
                val at = row.indexOf('\t')
                if (at <= 0) return@mapNotNull null
                val ms = row.substring(0, at).toLongOrNull() ?: return@mapNotNull null
                val text = row.substring(at + 1)
                if (text.isEmpty()) null else Line(ms, text)
            }.takeIf { it.size >= MIN_HUMAN && gapOk(it) }
        }.getOrNull()
    }

    /** 缓存也要过同一道行距检查：判据改了之后，旧缓存里的误识别不能继续用 */
    private fun gapOk(lines: List<Line>): Boolean {
        if (lines.size < 2) return false
        return (lines.last().startMs - lines.first().startMs) / lines.size >= MIN_LINE_GAP_MS
    }

    private fun write(cid: Long, lines: List<Line>) {
        val f = cacheFile(cid) ?: return
        runCatching {
            f.writeText(lines.joinToString("\n") { "${it.startMs}\t${it.text}" })
            trim()
        }
    }

    /** 超上限就按修改时间删最旧的。一次只删几个，不必每次扫全表 */
    private fun trim() {
        val files = dir?.listFiles() ?: return
        if (files.size <= CACHE_MAX) return
        files.sortedBy { it.lastModified() }
            .take(files.size - CACHE_MAX)
            .forEach { it.delete() }
    }

    // ------------------------------------------------------------ 接口结构

    @Serializable
    private data class PlayerResp(
        val code: Int = 0,
        val message: String = "",
        val data: PlayerData? = null,
    )

    @Serializable
    private data class PlayerData(val subtitle: SubtitleBlock? = null)

    @Serializable
    private data class SubtitleBlock(val subtitles: List<SubtitleItem> = emptyList())

    @Serializable
    private data class SubtitleItem(
        val lan: String = "",
        @SerialName("subtitle_url") val url: String = "",
        @SerialName("subtitle_url_v2") val urlV2: String = "",
    )

    @Serializable
    private data class SubtitleDoc(val body: List<Cue> = emptyList())

    @Serializable
    private data class Cue(val from: Double = 0.0, val content: String = "")

    /**
     * 一行歌词。
     *
     * [requireMark] 只有 AI 字幕才为 true：那种字幕把整段语音都转出来，
     * 唱的那几句被 ♪ 包起来，说话的不包，♪ 是唯一的切分依据。
     * 人工字幕整份都是词，没有这个记号，要求 ♪ 等于把整份丢掉。
     */
    private fun Cue.toLine(requireMark: Boolean): Line? {
        if (requireMark && !content.contains('♪')) return null
        val text = content.replace("♪", "").replace("♫", "").trim()
        if (text.isEmpty()) return null
        return Line(Math.round(from * 1000), text)
    }
}
