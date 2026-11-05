package com.kunplayer.tv.net

import com.kunplayer.tv.model.ApiResp
import com.kunplayer.tv.model.NewListResp
import com.kunplayer.tv.model.PopRankItem
import com.kunplayer.tv.model.PopRankResp
import com.kunplayer.tv.model.RankData
import com.kunplayer.tv.model.RankItem
import com.kunplayer.tv.model.RegionArchive
import com.kunplayer.tv.model.RegionFeedResp
import com.kunplayer.tv.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * 首页三个曲目来源。
 *
 * 三条路各自解决一件事，不再互相拼接：
 *   · region/feed/rcmd               音乐区推荐流 —— 首页「音乐」。顺序本身就是信息
 *   · music/comprehensive/web/rank   音乐综合榜   —— 首页「流行」。曲库口径，不是分区口径
 *   · newlist                        分区最新投稿 —— 首页「最新发布」
 *
 * 前两条都不返回 tname，所以首页不再按分类分节：分节的前提是数据带得动分类，
 * 而推荐流已经排好序，再切一刀只会把它打散。
 *
 * 三条路的共同点是「可能混进不是歌的东西」：推荐流里有乐评合集、专辑开箱、
 * 几小时的直播回放。所以时长闸门统一放在这里，而不是让每个页面各写一遍——
 * 那个阈值是靠听感调出来的，散开就会漂。
 */
object FeedApi {

    private const val BASE = "https://api.bilibili.com"

    /** 音乐区 rid，newlist 用 */
    private const val MUSIC_RID = 3

    /** 音乐下的二级分区号。注意不是 rid=3：1003 是「音乐」页的推荐位 */
    private const val REGION_MUSIC = 1003

    /** 推荐流一页 15 条，是接口的固定口径；多拉几页才有够滚的长度 */
    private const val REGION_PAGE_SIZE = 15
    private const val REGION_PAGES = 4
    private const val REGION_WEB_LOCATION = "333.40138"

    /** 综合榜一页 30 首。它按曲库排，同一位歌手的歌会连着出现，条数少了像个人专辑 */
    private const val POP_SIZE = 30
    private const val POP_WEB_LOCATION = "333.1351"

    /**
     * 30 秒以下多半是片段/预告；20 分钟以上多半是合辑或直播回放，按 OK 后要等半小时才有下一首。
     * 音乐区推荐流实测 75 条里 38 条超 20 分钟——这个闸门不是保险，是主力过滤器。
     */
    private const val MIN_SEC = 30
    private const val MAX_SEC = 1200

    /**
     * 音乐区十二个二级分区里，能当歌听的八个。
     *
     * 实测 rid ↔ 分区名一一对应（搜索结果逐条比对的，不是猜的）：
     * 28 原创音乐 / 29 音乐现场 / 30 VOCALOID·UTAU / 31 翻唱 /
     * 59 演奏 / 130 音乐综合 / 193 MV / 265 AI音乐。
     *
     * 这是全项目最硬的一条判据：B 站自己给稿件打的分区，比「标题里有没有
     * 开箱/盘点」可靠一个量级——标题匹配会漏（叫《XX 精选集》的盘点一条都挡不住），
     * 也会错杀（歌名里带「教学」两个字）。
     */
    private val SONG_TNAME = setOf(
        "原创音乐", "翻唱", "演奏", "MV",
        "VOCALOID·UTAU", "音乐综合", "音乐现场", "AI音乐",
    )

    /**
     * 同一个音乐区里，明确不是歌的四个。
     *
     * 243 乐评盘点在新版分区里叫「电台·歌单」——几小时的循环歌单、盘点视频
     * 全在这一格，是长视频的主力来源；244 音乐教学、267 电台、266 粉丝饭拍同理。
     * 这四个不进白名单，单独列出来是为了让「为什么挡它」有据可查。
     */
    private val NO_SONG_TNAME = setOf("乐评盘点", "音乐教学", "电台", "音乐粉丝饭拍")

    /** 推荐池的时长闸门。比首页通用的 30s~20min 更紧：这是「歌」的池子，不是「内容」的池子 */
    private const val SONG_MIN_SEC = 40
    private const val SONG_MAX_SEC = 900

    /** 推荐池上限。60 首够翻三屏，再多就只是把同一批歌换个顺序 */
    private const val REC_LIMIT = 60
    private const val REC_NEW_PAGES = 3

    /**
     * 推荐流里混进来的非歌曲内容。
     *
     * from_region=1003 是「音乐」大区，不是某个具体分区，所以新闻号、乐评号、
     * 专辑开箱会出现在同一条流里：实测 3 页 45 条里有 5 条是这一类——
     * 「封面新闻」发讣告、「央视网」发时政、「盘点十月爆火的梗曲」。
     * 少一条这类，比多十条歌重要：按确认放出一条新闻，这一页就不可信了。
     *
     * 只在标题与 UP 主上做最保守的匹配。宁可放过一条，不要错杀一首——
     * 「翻唱 / 演奏 / MV / Live / 现场」这些既可能是歌也可能是噪音的词，一个都不挡。
     *
     * **这一路是唯一没有 tname 的**：`region/feed/rcmd` 不返回分区名，
     * 所以只能靠标题猜。排行榜与最新投稿都带 tname，那两路已经在
     * `RankItem.toTrack()` 里用分区名挡掉 `NO_SONG_TNAME`，不必再猜。
     */
    private val NOISE_TITLE = listOf(
        "开箱", "乐评", "盘点", "教学", "教程", "饭拍", "访谈", "直播回放", "reaction",
        "解读",
    )
    private val NOISE_OWNER = listOf(
        "新闻", "资讯", "日报", "晚报", "快讯", "央视", "新华社",
    )

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    // ------------------------------------------------------------ 音乐（分区推荐流）

    /**
     * 音乐区推荐流。三页并行拉，失败一页不影响其余——电视上「半屏内容」
     * 永远好过「一屏错误」，而三页里挂一页的概率并不低。
     */
    fun musicRegion(log: (String) -> Unit): List<Track> {
        prepare(log)

        val pages = runBlocking {
            coroutineScope {
                (1..REGION_PAGES).map { pn ->
                    async(Dispatchers.IO) {
                        runCatching { regionPage(pn) }.getOrElse {
                            log("音乐区第 ${pn} 页失败 ${it.javaClass.simpleName}: ${it.message}")
                            emptyList()
                        }
                    }
                }.awaitAll()
            }
        }

        val list = pages.flatten().distinctBy { it.bvid }
        if (list.isEmpty()) throw BiliException("音乐区推荐没拿到")
        log("音乐区推荐 ${list.size} 首")
        return list
    }

    private fun regionPage(pn: Int): List<Track> {
        val params = linkedMapOf(
            "display_id" to pn.toString(),
            "request_cnt" to REGION_PAGE_SIZE.toString(),
            "from_region" to REGION_MUSIC.toString(),
            "device" to "web",
            "plat" to "30",
            "web_location" to REGION_WEB_LOCATION,
        )
        // WBI key 没拿到时退回明文：这个接口对签名不是硬要求，
        // 但拿不到 key 通常意味着 nav 也挂了，那一步已经在 prepare 里记过日志
        val query = if (Wbi.ready()) {
            Wbi.sign(params)
        } else {
            params.entries.joinToString("&") { "${it.key}=${it.value}" }
        }

        val r = BiliHttp.get("$BASE/x/web-interface/region/feed/rcmd?$query")
        if (!r.isOk) throw BiliException("region feed HTTP ${r.code} ${r.preview}")
        val resp = runCatching { json.decodeFromString<RegionFeedResp>(r.body) }.getOrNull()
            ?: throw BiliException("region feed 解析失败：${r.preview}")
        if (resp.code != 0) throw BiliException("region feed code=${resp.code} ${resp.message}")
        return resp.data?.archives.orEmpty().mapNotNull { it.toTrack() }
    }

    // ------------------------------------------------------------ 流行（音乐综合榜）

    /**
     * 音乐综合榜。条目里嵌着 related_archive（真正带 cid 的那条稿件），
     * 顶层的 bvid 是曲库元数据自己的稿件，两者不能混着取。
     */
    fun popRank(log: (String) -> Unit): List<Track> {
        val url = "$BASE/x/centralization/interface/music/comprehensive/web/rank" +
            "?pn=1&ps=$POP_SIZE&web_location=$POP_WEB_LOCATION"
        val r = BiliHttp.get(url)
        log("综合榜 HTTP ${r.code}")
        if (!r.isOk) throw BiliException("综合榜 HTTP ${r.code} ${r.preview}")
        val resp = runCatching { json.decodeFromString<PopRankResp>(r.body) }.getOrNull()
            ?: throw BiliException("综合榜解析失败：${r.preview}")
        if (resp.code != 0) throw BiliException("综合榜 code=${resp.code} ${resp.message}")
        val list = resp.data?.list.orEmpty().mapNotNull { it.toTrack() }
        log("综合榜 ${list.size} 首")
        return list
    }

    // ------------------------------------------------------------ 最新发布

    /** 音乐区最新投稿 */
    fun newest(log: (String) -> Unit): List<Track> {
        val r = BiliHttp.get("$BASE/x/web-interface/newlist?rid=$MUSIC_RID&ps=30&pn=1")
        log("newlist HTTP ${r.code}")
        if (!r.isOk) throw BiliException("newlist HTTP ${r.code} ${r.preview}")
        val resp = runCatching { json.decodeFromString<NewListResp>(r.body) }.getOrNull()
            ?: throw BiliException("newlist 解析失败：${r.preview}")
        val list = resp.data?.archives.orEmpty().mapNotNull { it.toTrack() }
        log("最新投稿 ${list.size} 首")
        return list
    }

    // ------------------------------------------------------------ 推荐音乐（纯歌池）

    /**
     * 首页「推荐音乐」。
     *
     * 排行榜给「热」，新投稿给「新」，两路都是完整稿件结构、都带 tname，
     * 所以合并之后还能按分区再筛一道。
     *
     * 合并先交替（一热一新），是为了两路都能进候选池；真正端给用户的那六十首
     * 是随机取的——理由见下面的注释，一句话：不随机，刷新就没有意义。
     *
     * 这里是全应用唯一一条「服务端没给时长参数、于是用最硬的本地判据兜住」
     * 的路：`newlist` 和 `ranking` 都不认 `duration`（传了也是逐字节相同的结果），
     * 只有搜索接口认。所以宁可少拿几首，也不放一首 40 分钟的盘点进来。
     */
    fun recommendMusic(log: (String) -> Unit): List<Track> {
        prepare(log)

        val hot = runCatching { rankMusic(log) }.getOrElse {
            log("音乐榜失败 ${it.javaClass.simpleName}: ${it.message}")
            emptyList()
        }
        val fresh = runCatching { newestPages(REC_NEW_PAGES, log) }.getOrElse {
            log("新投稿失败 ${it.javaClass.simpleName}: ${it.message}")
            emptyList()
        }
        if (hot.isEmpty() && fresh.isEmpty()) throw BiliException("推荐池没拿到内容")

        val pool = ArrayList<Track>(hot.size + fresh.size)
        var i = 0
        while (i < maxOf(hot.size, fresh.size)) {
            if (i < hot.size) pool += hot[i]
            if (i < fresh.size) pool += fresh[i]
            i++
        }

        val list = pool
            .distinctBy { it.bvid }
            .filter { it.category in SONG_TNAME }
            .filter { it.durationSec in SONG_MIN_SEC..SONG_MAX_SEC }

        // 候选池大约一百首，一次只端出六十首。
        //
        // 这是「刷新」能不能被看见的关键：榜单和新投稿在几分钟内是同一批东西，
        // 老老实实取前六十，用户再按一次「推荐音乐」会拿到一模一样的一屏——
        // 按钮像是坏的。所以从全部候选里随机取：同一批数据也能端出不同的组合，
        // 翻两次不会一直是那几首。
        //
        // 池子不够六十首时按原顺序全给（这时随机没有意义，还会把仅有的顺序打乱）。
        val out = if (list.size > REC_LIMIT) list.shuffled().take(REC_LIMIT) else list

        log("推荐池 热${hot.size} 新${fresh.size} 候选${list.size} → 端出 ${out.size}")
        return out
    }

    // ------------------------------------------------------------ 长合集

    /**
     * 「推荐音乐」这一页的取词池。
     *
     * 它不是搜索建议，是「端一屏长合集」的词库：每次进页面随机抽几个，
     * 各自的搜索结果取并集——抽中的词之间是**或**的关系，命中任意一个就进池。
     * 所以同一批词能端出无数种组合，刷新不必换数据源。
     *
     * 词分三类，故意混着抽：
     *   · 内容形态（全集 / 歌单 / 完整版 / 不分P / 演唱会 / 现场）—— 指向长音频
     *   · 曲库口味（华语 / 粤语 / 怀旧 / 老歌 / 民谣 / 黑胶）
     *   · 平台与载体（抖音 / 车载 / U盘 / 网易云 / QQ音乐）—— 搬运号都这么起名
     *
     * 单字词（首 / 70 / 80 / 90 / 00）也留着：它们几乎只出现在「100 首」
     * 「70 后」这类合集标题里，配上音乐分区与一小时闸门，噪声很低。
     */
    private val LONG_KEYWORDS = listOf(
        "时长", "华语", "怀旧", "歌手", "中年", "首", "抖音", "快手",
        "精选", "成名曲", "神仙", "金曲", "粤语", "闽南语", "日语", "韩语",
        "英语", "英文", "全集", "纯音乐", "歌单", "完整版", "无损", "Hi-res",
        "长途", "老歌", "经典", "神曲", "70", "80", "90", "00",
        "MV", "好歌", "黑胶", "唱片", "民谣", "对唱", "火爆", "极品",
        "试音", "车载", "U盘", "网络", "台湾", "香港", "大陆", "中文",
        "影视", "演唱会", "现场", "不分P", "分P",
        "网易云", "QQ音乐", "汽水音乐", "酷狗", "酷我",
    )

    /** 一次抽几个词。三个起——两个词凑不齐三十首；五个以上就只是在等网络 */
    private const val LONG_PICK = 3

    /** 端出多少首 */
    private const val LONG_LIMIT = 30

    /** 最多抽几轮。首轮不够三十首才补第二、第三轮，不为凑数无限打接口 */
    private const val LONG_ROUNDS = 3

    /** 两次搜索之间歇一下。连打会被风控，而 -352 和「没结果」在日志里长得一样 */
    private const val LONG_GAP_MS = 250L

    /**
     * 长合集里混进来的「不是歌」。
     *
     * 关键词里有「英语」「英文」「日语」「韩语」，配上「一小时以上」，搜出来
     * 最多的不是外文歌，是**外语教材**——实测第一屏第一条就是「《分类记 18000
     * 英语单词》有声书·第 08–17 部」，十几小时，分区还落在 MV，分区闸门挡不住。
     *
     * 只列这类「教材 / 课程 / 有声书」词：它们在歌曲合集标题里几乎不出现，
     * 挡错的代价也小。跟 [NOISE_TITLE] 是两码事——那张表在猜「这是不是歌」，
     * 这张表只排「这根本不是音乐」。
     */
    private val LONG_NOISE = listOf(
        "有声书", "单词", "背单词", "英语学习", "日语学习", "韩语学习",
        "四六级", "雅思", "托福", "听力", "教程", "课程", "讲座", "讲解", "教学",
    )

    /** 标题里带 [LONG_NOISE] 里任何一个词的，从长合集池里去掉 */
    private fun Track.isLongNoise(): Boolean {
        val t = title.lowercase()
        return LONG_NOISE.any { t.contains(it) }
    }

    /**
     * 推荐音乐：随机抽关键词，搜一小时以上的合集，取并集端出三十条。
     *
     * 与 [recommendMusic] 是两条互不相干的路，别合并：
     *   · [recommendMusic] 找的是**单曲**，靠分区白名单 + 40s~15min 闸门把
     *     合集挡在外面，给「推荐 UP 主」当底料
     *   · 这条路要的恰恰是被挡住的东西：一小时以上的歌单、串烧、演唱会全场，
     *     所以它反过来只留长、只留音乐大区
     *
     * 每次调用都重新抽词，所以每次进页面都是新的一屏。抽词随机是这里
     * 唯一的「推荐算法」——它不猜你喜欢什么，它换一批让你自己挑。
     *
     * **必须在 IO 线程调用**：中间有 Thread.sleep 给风控留间隔。
     */
    fun recommendCollections(log: (String) -> Unit): List<Track> {
        val words = LONG_KEYWORDS.shuffled()
        val pool = LinkedHashMap<String, Track>()
        var used = 0
        var round = 0

        while (round < LONG_ROUNDS && pool.size < LONG_LIMIT) {
            val picks = words.drop(used).take(LONG_PICK)
            if (picks.isEmpty()) break
            used += picks.size
            round++
            log("长合集 第${round}轮 ${picks.joinToString("/")}")

            for (kw in picks) {
                val got = runCatching { SearchApi.searchLong(kw, log = log) }.getOrElse {
                    log("长合集「$kw」失败 ${it.javaClass.simpleName}: ${it.message}")
                    emptyList()
                }
                // 同一个合集会被多个关键词命中（「老歌」和「精选」搜到同一张
                // 歌单是常态），按 bvid 去重。
                //
                // 不能写 pool.putIfAbsent：那是 Java 8 的 Map 默认方法，
                // API 23 的 core-libart 里没有，电视上会直接 NoSuchMethodError。
                got.filterNot { it.isLongNoise() }
                    .forEach { if (!pool.containsKey(it.bvid)) pool[it.bvid] = it }
                if (pool.size >= LONG_LIMIT * 2) break
                Thread.sleep(LONG_GAP_MS)
            }
        }

        if (pool.isEmpty()) throw BiliException("长合集没拿到内容")

        val all = pool.values.toList()
        // 候选多于三十就随机取：候选池是「六到九路搜索结果」，顺序本来就是
        // 关键词的拼贴，取前三十等于每次都先给同一路的结果
        val out = if (all.size > LONG_LIMIT) all.shuffled().take(LONG_LIMIT) else all
        // 时长区间写进日志：「都超过一小时」这条是需求硬指标，别只靠眼睛看几行
        log("长合集 候选${all.size} 时长${all.minOf { it.durationSec } / 60}~${all.maxOf { it.durationSec } / 60}分 → 端出 ${out.size}")
        return out
    }

    /** 音乐区排行榜。rid=3 是父分区，返回的每一条都带自己的 tname */
    private fun rankMusic(log: (String) -> Unit): List<Track> {
        val r = BiliHttp.get("$BASE/x/web-interface/ranking/v2?rid=$MUSIC_RID&type=all")
        if (!r.isOk) throw BiliException("音乐榜 HTTP ${r.code} ${r.preview}")
        val resp = r.body.decodeApi<ApiResp<RankData>>("ranking/v2")
        if (resp.code != 0) throw BiliException("音乐榜 code=${resp.code} ${resp.message}")
        return resp.data?.list.orEmpty().mapNotNull { it.toTrack() }
    }

    /**
     * 分区最新投稿，多页并行。
     *
     * 只取父分区 rid=3：子分区的 rid 在 newlist 上一律返回空列表
     * （28/31/59 全试过），拿不到就分不出「翻唱今天发了什么」。
     */
    private fun newestPages(pages: Int, log: (String) -> Unit): List<Track> {
        val got = runBlocking {
            coroutineScope {
                (1..pages).map { pn ->
                    async(Dispatchers.IO) {
                        runCatching { newestPage(pn) }.getOrElse {
                            log("新投稿第 $pn 页失败 ${it.javaClass.simpleName}: ${it.message}")
                            emptyList()
                        }
                    }
                }.awaitAll()
            }
        }
        return got.flatten()
    }

    private fun newestPage(pn: Int): List<Track> {
        val r = BiliHttp.get("$BASE/x/web-interface/newlist?rid=$MUSIC_RID&ps=20&pn=$pn")
        if (!r.isOk) throw BiliException("newlist HTTP ${r.code} ${r.preview}")
        val resp = r.body.decodeApi<NewListResp>("newlist")
        if (resp.code != 0) throw BiliException("newlist code=${resp.code} ${resp.message}")
        return resp.data?.archives.orEmpty().mapNotNull { it.toTrack() }
    }

    // ------------------------------------------------------------ 前置条件

    /**
     * 推荐流与播放地址都依赖两样东西：WBI key 和 buvid3。
     *
     * buvid3 尤其要紧：分区推荐流缺了它返回 **code 62013「暂时没有更多内容了」**。
     * 这个码看起来像「这个分区没内容」，实际是缺设备指纹——一个谎报的空列表，
     * 排查时会先去怀疑分区号，越查越远。
     */
    private fun prepare(log: (String) -> Unit) {
        if (!Wbi.ready()) runCatching { BiliApi.fetchNav(log) }
        if (!CookieStore.has("buvid3")) runCatching { BiliApi.activateBuvid(log) }
    }

    // ------------------------------------------------------------ 映射

    private fun RankItem.toTrack(): Track? {
        if (bvid.isEmpty() || cid <= 0) return null
        if (duration !in MIN_SEC..MAX_SEC) return null
        // 这一条是不是「歌」，B 站自己标过了。排行榜与最新投稿都带 tname，
        // 挡在这里就不必再靠标题关键词猜——「盘点」「教学」这类词在标题里
        // 出现与否全凭 UP 主心情，分区名不是
        if (tname in NO_SONG_TNAME) return null
        return Track(
            bvid = bvid,
            aid = aid,
            cid = cid,
            title = title.trim(),
            cover = coverUrl(pic),
            owner = owner?.name.orEmpty(),
            ownerMid = owner?.mid ?: 0L,
            durationSec = duration,
            category = tname,
            // 最新发布带 videos：多 P 稿件在这里就能标成合集
            pages = videos,
        )
    }

    private fun RegionArchive.toTrack(): Track? {
        if (bvid.isEmpty() || cid <= 0) return null
        if (duration !in MIN_SEC..MAX_SEC) return null
        val who = author?.name.orEmpty()
        if (noisy(title, who)) return null
        return Track(
            bvid = bvid,
            aid = aid,
            cid = cid,
            title = title.trim(),
            cover = coverUrl(cover),
            owner = who,
            ownerMid = author?.mid ?: 0L,
            durationSec = duration,
        )
    }

    private fun noisy(title: String, owner: String): Boolean {
        if (NOISE_OWNER.any { owner.contains(it) }) return true
        return NOISE_TITLE.any { title.contains(it, ignoreCase = true) }
    }

    /**
     * 这个接口的两组字段是**交叉**的，取错任何一半都会让 playurl 返回 -404「啥都木有」：
     *
     *   · related_archive.bvid  才是可播的那条稿件；顶层的 bvid 是曲库条目，播不了
     *   · 顶层 cid              才是这条稿件的 cid；related_archive.cid 是曲库音频 id
     *
     * 实测 8 首：关联 bvid + 顶层 cid 全部 200，其余三种组合全部 -404。
     * 这类错误在播放器里表现为「一直跳到下一首」，从日志上看只是一串 -404，
     * 所以把结论写在这里，别再凭直觉取 related_archive.cid。
     */
    private fun PopRankItem.toTrack(): Track? {
        val archive = relatedArchive
        val bv = archive?.bvid.orEmpty()
        val cidValue = cid.toLongOrNull() ?: 0L
        if (bv.isEmpty() || cidValue <= 0) return null

        val sec = archive?.duration ?: 0
        if (sec > 0 && sec !in MIN_SEC..MAX_SEC) return null

        return Track(
            bvid = bv,
            aid = archive?.aid?.toLongOrNull() ?: 0L,
            cid = cidValue,
            title = archive?.title?.takeIf { it.isNotEmpty() } ?: musicTitle,
            cover = coverUrl(archive?.cover?.takeIf { it.isNotEmpty() } ?: cover),
            owner = archive?.username?.takeIf { it.isNotEmpty() } ?: author,
            ownerMid = archive?.uid?.toLongOrNull() ?: 0L,
            durationSec = sec,
        )
    }

    /**
     * 封面必须走 CDN 的缩放样式：原图 281KB，480×270 的 webp 只要 9.7KB。
     * 一屏四行就是 4MB 与 40KB 的差别，A53 上解码原图会直接卡住列表。
     *
     * 另外 pic 返回的是 http，必须提到 https——清单里 usesCleartextTraffic=false，
     * 明文请求会被直接拒掉，而且失败得很安静（卡片就一直是深色底）。
     */
    fun coverUrl(raw: String, width: Int = 480, height: Int = 270): String {
        if (raw.isEmpty()) return ""
        val https = if (raw.startsWith("http://")) "https://" + raw.removePrefix("http://") else raw
        if (https.contains('@')) return https
        return "$https@${width}w_${height}h_1c.webp"
    }

    /**
     * 换一种裁法重新向 CDN 要同一张图。
     *
     * 缓存里存的是 16:9（480×270），而方形货架要 1:1、Hero 要大图。与其下载原图在本地裁，
     * 不如把尺寸参数改掉让 CDN 裁好再给——服务器端的 `1c` 就是居中裁切，省带宽也省一次上采样。
     */
    fun reCrop(cover: String, width: Int, height: Int): String {
        if (cover.isEmpty()) return ""
        val base = cover.substringBefore('@')
        return "$base@${width}w_${height}h_1c.webp"
    }
}
