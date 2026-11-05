package com.kunplayer.tv.net

import com.kunplayer.tv.model.ApiResp
import com.kunplayer.tv.model.ArcItem
import com.kunplayer.tv.model.ArcSearchData
import com.kunplayer.tv.model.FollowUser
import com.kunplayer.tv.model.FollowingsData
import com.kunplayer.tv.model.PageItem
import com.kunplayer.tv.model.SpaceUser
import com.kunplayer.tv.model.Track

/**
 * 用户关系与 UP 主主页。
 *
 * 三个接口：
 *   · `x/relation/followings`            我关注的人（注意路径：老路径
 *                                         /x/web-interface/relations/followings 已 404）
 *   · `x/space/wbi/acc/info`             UP 主资料，含 is_followed
 *   · `x/space/wbi/arc/search`           UP 主投稿列表
 *
 * 两个 space 接口都要 WBI 签名，签名不成就是 412——和风控长得一模一样，
 * 所以这里统一在 Wbi 没就绪时先补一次 nav。
 *
 * 投稿列表**不返回 cid**（只有 aid/bvid/时长），而取流必须要有 cid。
 * 补 cid 用 `x/player/pagelist`（比 view 轻得多，只给分 P 表），
 * 而不是 x/web-interface/view——后者会把整个稿件详情都拉回来。
 */
object UserApi {

    private const val BASE = "https://api.bilibili.com"

    /** 我关注的人一页 30 个。电视上翻关注列表的人不会超过这个数 */
    private const val FOLLOW_PAGE_SIZE = 30

    /** UP 主投稿一页 30 条 */
    private const val ARC_PAGE_SIZE = 30

    /**
     * 我关注的人。
     *
     * @param page 从 1 起
     * @param ps   一页多少个。默认 30（够翻关注列表用）；建「已关注集合」时
     *             会传更大的值，因为那里要的是「把名单尽量一次拿完」，
     *             一页三十个翻十页就是十次请求
     */
    fun followings(page: Int = 1, log: (String) -> Unit = {}, ps: Int = FOLLOW_PAGE_SIZE): List<FollowUser> {
        val mid = LoginApi.mid()
        if (mid <= 0) throw BiliException("未登录，看不到关注列表")
        val r = BiliHttp.get(
            "$BASE/x/relation/followings?vmid=$mid&pn=$page&ps=$ps" +
                "&order=desc&tid=0&platform=web",
        )
        log("关注列表 HTTP ${r.code}")
        if (!r.isOk) throw BiliException("关注列表 HTTP ${r.code} ${r.preview}")
        val resp = r.body.decodeApi<ApiResp<FollowingsData>>("relation/followings")
        if (resp.code != 0) throw BiliException("关注列表 code=${resp.code} ${resp.message}")
        val list = resp.data?.list.orEmpty()
        log("关注 ${resp.data?.total ?: list.size} 人，取到 ${list.size}")
        return list
    }

    /** UP 主资料 */
    fun spaceInfo(mid: Long, log: (String) -> Unit = {}): SpaceUser {
        if (mid <= 0) throw BiliException("作者 id 无效")
        prepareWbi(log)
        val query = Wbi.sign(linkedMapOf("mid" to mid.toString()))
        val r = BiliHttp.get("$BASE/x/space/wbi/acc/info?$query")
        log("UP 资料 HTTP ${r.code}")
        if (!r.isOk) throw BiliException("UP 资料 HTTP ${r.code} ${r.preview}")
        val resp = try {
            r.body.decodeApi<ApiResp<SpaceUser>>("space/acc/info")
        } catch (t: Throwable) {
            // 这个接口的字段类型会随账号变。把原文记下来，才知道是哪个字段对不上，
            // 而不是对着「解析失败」四个字猜
            log("UP 资料原文 ${r.body.take(600)}")
            throw t
        }
        if (resp.code != 0) throw BiliException("UP 资料 code=${resp.code} ${resp.message}")
        return resp.data ?: throw BiliException("UP 资料没有 data")
    }

    /**
     * UP 主的投稿列表。
     *
     * 返回的条目没有 cid，所以这里顺带打一次 pagelist 把 cid 补上——
     * 否则点开只能报错。pagelist 对合集会返回全部 P，
     * 这里只取第一 P（首页那一栏的歌就是 P1）。
     */
    fun spaceVideos(mid: Long, page: Int = 1, log: (String) -> Unit = {}): List<Track> {
        if (mid <= 0) throw BiliException("作者 id 无效")
        prepareWbi(log)
        val query = Wbi.sign(
            linkedMapOf(
                "mid" to mid.toString(),
                "pn" to page.toString(),
                "ps" to ARC_PAGE_SIZE.toString(),
                "order" to "pubdate",
                "platform" to "web",
                "web_location" to "1550101",
            ),
        )
        val r = BiliHttp.get("$BASE/x/space/wbi/arc/search?$query")
        log("UP 投稿 HTTP ${r.code}")
        if (!r.isOk) throw BiliException("UP 投稿 HTTP ${r.code} ${r.preview}")
        val resp = r.body.decodeApi<ApiResp<ArcSearchData>>("space/arc/search")
        if (resp.code != 0) throw BiliException("UP 投稿 code=${resp.code} ${resp.message}")
        val vlist = resp.data?.list?.vlist.orEmpty()
        log("UP 投稿 ${vlist.size} 条")
        return vlist.mapNotNull { it.toTrack() }
    }

    /**
     * 关注 / 取关。
     *
     * act：1 = 关注，2 = 取关。已经在关注时再发 act=1 会返回 22014，
     * 这不是失败，是「本来就在关注」，调用方按自己维护的状态判断即可。
     */
    fun setFollow(mid: Long, follow: Boolean, log: (String) -> Unit = {}): Boolean {
        if (mid <= 0) throw BiliException("作者 id 无效")
        val csrf = LoginApi.csrf()
        if (csrf.isEmpty()) throw BiliException("未登录，无法关注")
        val r = BiliHttp.postForm(
            "$BASE/x/relation/modify",
            mapOf(
                "fid" to mid.toString(),
                "act" to if (follow) "1" else "2",
                "re_src" to "11",
                "csrf" to csrf,
            ),
        )
        log("关注操作 HTTP ${r.code}")
        if (!r.isOk) throw BiliException("关注 HTTP ${r.code} ${r.preview}")
        val resp = r.body.decodeApi<ApiResp<kotlinx.serialization.json.JsonElement>>("relation/modify")
        if (resp.code == 22014) {
            // 已经关注了。视为成功——用户点的目标已经达成
            log("本来就在关注")
            return true
        }
        if (resp.code != 0) throw BiliException("关注 code=${resp.code} ${resp.message}")
        return true
    }

    /** 取某稿件的分 P 表。轻量接口，只给 cid 与 part 名 */
    private fun pagelist(bvid: String, log: (String) -> Unit): List<PageItem> {
        val r = BiliHttp.get("$BASE/x/player/pagelist?bvid=$bvid")
        if (!r.isOk) return emptyList()
        val resp = r.body.decodeApi<ApiResp<List<PageItem>>>("player/pagelist")
        return resp.data.orEmpty()
    }

    private fun prepareWbi(log: (String) -> Unit) {
        if (!Wbi.ready()) runCatching { BiliApi.fetchNav(log) }
    }

    private fun ArcItem.toTrack(): Track? {
        if (bvid.isEmpty()) return null
        val sec = parseLength(length)
        if (sec <= 0) return null
        return Track(
            bvid = bvid,
            aid = aid,
            cid = 0,   // 下面补
            title = cleanTitle(title),
            cover = FeedApi.coverUrl(pic.httpsUrl()),
            owner = author,
            ownerMid = mid,
            durationSec = sec,
        )
    }

    /**
     * 补 cid。
     *
     * 只给第一 P：这个列表展示的是「UP 发了什么」，不是「合集里第几首」。
     * 合集本身由 CollectionApi 负责展开，两件事分开才不会互相拖累。
     */
    fun fillCid(tracks: List<Track>, log: (String) -> Unit): List<Track> {
        if (tracks.isEmpty()) return tracks
        return tracks.map { t ->
            if (t.cid > 0) return@map t
            val pages = pagelist(t.bvid, log)
            val first = pages.firstOrNull() ?: return@map t
            t.copy(cid = first.cid, pageNo = 1)
        }
    }

    /** `165:23` 这种写法。B 站的 length 一律是分:秒，超过一小时也是这个格式 */
    private fun parseLength(text: String): Int {
        if (text.isBlank()) return 0
        val parts = text.split(':').mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0
        }
    }

    /** 投稿标题带 `<em class="keyword">` 高亮标签，上屏前要剥掉 */
    private val emTag = Regex("<[^>]+>")

    private fun cleanTitle(raw: String): String = raw
        .replace(emTag, "")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .trim()
}
