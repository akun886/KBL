package com.kunplayer.tv.net

import com.kunplayer.tv.model.HistoryResp
import com.kunplayer.tv.model.Track

/**
 * 观看历史（`x/web-interface/history/cursor`）。
 *
 * 必须是登录态，否则 code=-101。历史条目自带 cid，播放时不用再打 view，
 * 所以这个页面是「打开就能播」的——这也是它比搜索更适合当默认入口的原因。
 *
 * 一次只要 30 条：电视上历史页不是考古的地方，看到上周听的歌就够了。
 */
object HistoryApi {

    private const val BASE = "https://api.bilibili.com"

    private const val PAGE_SIZE = 30

    fun recent(): List<Track> {
        // 别加 type=video：带上它服务端直接 -400「请求错误」，
        // 不带反而只返回视频（tab 里明确写着 archive/live/article，默认就是 archive）。
        val r = BiliHttp.get("$BASE/x/web-interface/history/cursor?max=0&view_at=0&business=&ps=$PAGE_SIZE")
        if (!r.isOk) throw BiliException("历史 HTTP ${r.code} ${r.preview}")
        val resp = r.body.decodeApi<HistoryResp>("history/cursor")
        if (resp.code != 0) throw BiliException("历史 code=${resp.code} ${resp.message}")

        // 同一个 bvid 反复出现（听了又听）只留最新一条，否则历史页第一行全是同一首歌
        val seen = HashSet<String>()
        return resp.data?.list.orEmpty()
            .mapNotNull { it.toTrack() }
            .filter { seen.add(it.bvid) }
    }

    private fun com.kunplayer.tv.model.HistoryItem.toTrack(): Track? {
        val meta = history ?: return null
        if (meta.bvid.isEmpty() || meta.business != "archive") return null
        return Track(
            bvid = meta.bvid,
            aid = meta.oid,
            cid = meta.cid,
            // 多 P 合集里 title 是合辑名（「100 首无损合集」），show_title 才是这一 P 的曲名；
            // 在音乐播放器里后者才是用户想看的那个
            title = showTitle.trim().ifEmpty { title.trim() },
            cover = FeedApi.coverUrl(cover.httpsUrl()),
            owner = authorName,
            ownerMid = authorMid,
            durationSec = duration,
            // 历史接口给了 P 数：列表里能直接标「合集 100 首」，不必等播过一次
            pages = videos,
            pageNo = meta.page,
        )
    }
}
