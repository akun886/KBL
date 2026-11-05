package com.kunplayer.tv.net

import com.kunplayer.tv.model.FavFolder
import com.kunplayer.tv.model.FavFolderResp
import com.kunplayer.tv.model.FavListResp
import com.kunplayer.tv.model.FavMedia
import com.kunplayer.tv.model.Track

/**
 * 云端收藏夹。
 *
 * 两级结构：先拿「我创建的收藏夹」，再逐个拿夹子里的视频。
 * 不做全量翻页——电视上没人会滚到第 8 个收藏夹的第 400 条，
 * 取前 5 个夹子、每夹 20 条就覆盖了绝大多数真实使用。
 *
 * 收藏资源里没有 cid（只有 aid/bvid），播放时由 PlaybackController 的
 * `fetchView` 兜底取 cid。这是刻意的：为 20 条收藏各打一次 view，
 * 就为了一个可能只看不播的列表，代价比播那一首时多等 150ms 大得多。
 */
object FavApi {

    private const val BASE = "https://api.bilibili.com"

    /** 首页列表最多铺几个夹子。八节高的一屏放不下更多 */
    const val MAX_FOLDERS = 5

    /**
     * 建收藏状态索引时最多扫几个夹子。
     *
     * 索引要回答的是「这一首在不在收藏里」，漏扫一个夹就可能把
     * 「已收藏」显示成未收藏——比多扫几个夹严重得多，所以比列表宽得多。
     */
    const val MAX_SCAN = 20

    const val PER_FOLDER = 20

    fun folders(mid: Long, log: (String) -> Unit = {}): List<FavFolder> {
        if (mid <= 0) return emptyList()
        val r = BiliHttp.get("$BASE/x/v3/fav/folder/created/list-all?up_mid=$mid")
        log("fav/folder HTTP ${r.code}")
        if (!r.isOk) throw BiliException("收藏夹列表 HTTP ${r.code} ${r.preview}")
        val resp = r.body.decodeApi<FavFolderResp>("fav/folder")
        if (resp.code != 0) throw BiliException("收藏夹列表 code=${resp.code} ${resp.message}")
        val list = resp.data?.list.orEmpty()
        log("收藏夹 ${list.size} 个：${list.joinToString { "${it.title}(${it.mediaCount})" }}")
        return list
    }

    fun resources(mediaId: Long, page: Int = 1, log: (String) -> Unit = {}): List<FavMedia> {
        val url = "$BASE/x/v3/fav/resource/list?media_id=$mediaId&pn=$page&ps=$PER_FOLDER" +
            "&keyword=&order=mtime&type=0&tid=0&platform=web"
        val r = BiliHttp.get(url)
        log("fav/resource($mediaId) HTTP ${r.code}")
        if (!r.isOk) throw BiliException("收藏内容 HTTP ${r.code} ${r.preview}")
        val resp = r.body.decodeApi<FavListResp>("fav/resource")
        if (resp.code != 0) throw BiliException("收藏内容 code=${resp.code} ${resp.message}")
        return resp.data?.medias.orEmpty()
    }

    /**
     * 云端收藏拍平成一条歌单（按夹子顺序拼接，同一首只留第一次出现的）。
     *
     * 首页不按夹子分节：侧栏的「我的收藏」是一个目的地，不是一组目的地。
     * 单个夹子失败只跳过它——电视上少一首远好过整页报错。
     */
    fun all(mid: Long, log: (String) -> Unit = {}): List<Track> {
        // 只跳过空夹子。attr=22 是「私密」，但那是我们自己账号里的夹子，
        // 带上本账号 Cookie 完全读得到——把它过滤掉，用户会以为自己的收藏丢了。
        val folders = folders(mid, log)
            .filter { it.mediaCount > 0 }
            .take(MAX_FOLDERS)

        val out = ArrayList<Track>()
        val seen = HashSet<String>()
        for (folder in folders) {
            // 这里用 try/catch 而不是 runCatching：后者是内联函数，
            // 里面不能 continue，写出来会被编译器判成实验特性
            val medias = try {
                resources(folder.id, log = log)
            } catch (t: Throwable) {
                log("收藏夹《${folder.title}》失败：${t.message}")
                continue
            }
            val tracks = medias.mapNotNull { it.toTrack() }
            log("收藏夹《${folder.title}》 ${tracks.size} 首")
            tracks.forEach { if (seen.add(it.bvid)) out += it }
        }
        return out
    }

    private fun FavMedia.toTrack(): Track? {
        if (bvid.isEmpty()) return null
        // attr != 0 是失效稿件（已删除/私有/退回），点进去只会取流失败
        if (attr != 0) return null
        return Track(
            bvid = bvid,
            aid = id,
            cid = 0,
            title = title.trim(),
            cover = FeedApi.coverUrl(cover),
            owner = upper?.name.orEmpty(),
            ownerMid = upper?.mid ?: 0L,
            durationSec = duration,
        )
    }
}
