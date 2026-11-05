package com.kunplayer.tv.net

import com.kunplayer.tv.model.SearchItem
import com.kunplayer.tv.model.SearchResp
import com.kunplayer.tv.model.Track

/**
 * 站内搜索（WBI 签名版）。
 *
 * 实测结论，决定了电视键盘该长什么样：
 *   · `keyword=周杰伦` 与 `keyword=zhoujielun` 返回的是同一批结果——
 *     搜索服务本身吃拼音，不需要先转成汉字；
 *   · 所以电视上只要一个 a-z 的 QWERTY 就够了，不必碰拼音输入法，
 *     也不必自建「拼音→汉字」的候选词表（那件事要做对，工作量是键盘的十倍）。
 *
 * 未登录可用，但要签名：不带 w_rid 会 412，和风控报错长得一模一样。
 */
object SearchApi {

    private const val BASE = "https://api.bilibili.com"

    /** 片段/预告按 OK 播没有意义；3 小时以上基本是合辑，切歌要等一小时 */
    private const val MIN_SEC = 20
    private const val MAX_SEC = 3 * 3600

    private val emTag = Regex("<[^>]+>")

    fun search(keyword: String, page: Int = 1, log: (String) -> Unit = {}): List<Track> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return emptyList()

        // 搜索必须签名；WBI key 没就绪时先补一次 nav，否则直接 412
        if (!Wbi.ready()) BiliApi.fetchNav { }

        val query = Wbi.sign(
            linkedMapOf(
                "search_type" to "video",
                "keyword" to kw,
                "page" to page.toString(),
                "page_size" to "30",
            )
        )
        val r = BiliHttp.get("$BASE/x/web-interface/wbi/search/type?$query")
        log("search HTTP ${r.code}")
        if (!r.isOk) throw BiliException("搜索 HTTP ${r.code} ${r.preview}")
        val resp = r.body.decodeApi<SearchResp>("search/type")
        if (resp.code != 0) throw BiliException("搜索 code=${resp.code} ${resp.message}")

        val data = resp.data
        val raw = data?.result.orEmpty()
        val list = raw.mapNotNull { it.toTrack() }
        log("搜索 ${raw.size} 条原始结果，可播 ${list.size} 条")
        return list
    }

    private fun SearchItem.toTrack(): Track? {
        if (bvid.isEmpty()) return null
        val seconds = parseDuration(duration)
        if (seconds <= 0 || seconds !in MIN_SEC..MAX_SEC) return null
        return Track(
            bvid = bvid,
            aid = if (aid > 0) aid else id,
            cid = 0,
            title = clean(title),
            cover = FeedApi.coverUrl(pic.httpsUrl()),
            owner = author,
            ownerMid = mid,
            durationSec = seconds,
        )
    }

    // ------------------------------------------------------------ 长合集

    /** 长合集的时长下限。正好一小时的不算——「大于 60 分钟」是字面意思 */
    private const val LONG_MIN_SEC = 60 * 60

    /** 搜索结果一页最多 50 条，再多接口直接报参数错误 */
    private const val LONG_PAGE_SIZE = 50

    /** 音乐大区。泛词（「时长」「网络」「首」）不带它就会搜回完全无关的稿件 */
    private const val MUSIC_TIDS = 3

    /**
     * 按关键词拉「长合集」——一小时以上的歌单、串烧、演唱会全场。
     *
     * 与 [search] 只差三个参数，但这三个参数就是这条路存在的全部理由：
     *
     *   · `duration=4` —— 服务端只回 60 分钟以上（1=0–10min、2=10–30、
     *     3=30–60、4=60+）。这是拉之前就筛掉，不是拉回来再丢。
     *   · `tids=3` —— 限定音乐大区。关键词里有「时长」「网络」「首」这种
     *     单字泛词，不限定分区会搜回一堆电视剧解说。
     *   · `page_size=50` —— 一步够 30 首，省两次翻页。
     *
     * 这里刻意**不套** [search] 那道 20s~3h 的闸门：那条闸门的存在意义
     * 就是挡掉这种长视频，而这条路要的正是它们。
     */
    fun searchLong(keyword: String, page: Int = 1, log: (String) -> Unit = {}): List<Track> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return emptyList()

        if (!Wbi.ready()) BiliApi.fetchNav { }

        val query = Wbi.sign(
            linkedMapOf(
                "search_type" to "video",
                "keyword" to kw,
                "page" to page.toString(),
                "page_size" to LONG_PAGE_SIZE.toString(),
                "duration" to "4",
                "tids" to MUSIC_TIDS.toString(),
            )
        )
        val r = BiliHttp.get("$BASE/x/web-interface/wbi/search/type?$query")
        if (!r.isOk) throw BiliException("长合集搜索 HTTP ${r.code} ${r.preview}")
        val resp = r.body.decodeApi<SearchResp>("search/type")
        if (resp.code != 0) throw BiliException("长合集搜索 code=${resp.code} ${resp.message}")

        val raw = resp.data?.result.orEmpty()
        val list = raw.mapNotNull { it.toLongTrack() }
        log("长合集「$kw」原始 ${raw.size} 条，超一小时 ${list.size} 条")
        return list
    }

    private fun SearchItem.toLongTrack(): Track? {
        if (bvid.isEmpty()) return null
        val seconds = parseDuration(duration)
        if (seconds <= LONG_MIN_SEC) return null
        return Track(
            bvid = bvid,
            aid = if (aid > 0) aid else id,
            cid = 0,
            title = clean(title),
            cover = FeedApi.coverUrl(pic.httpsUrl()),
            owner = author,
            ownerMid = mid,
            durationSec = seconds,
            category = typename,
        )
    }

    /** 结果标题带 `<em class="keyword">` 高亮标签，直接上屏会是一串尖括号 */
    private fun clean(raw: String): String = raw
        .replace(emTag, "")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .trim()

    /**
     * `4:38` / `419:2` / `1:02:33` 三种写法。
     * 注意第二种：B 站对超一小时的老视频不给小时位，直接把总分钟数写在最前面。
     */
    private fun parseDuration(text: String): Int {
        if (text.isBlank()) return 0
        val parts = text.split(':').mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0
        }
    }
}
