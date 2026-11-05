package com.kunplayer.tv.net

import com.kunplayer.tv.model.Track

/**
 * 首页「推荐 UP 主」。
 *
 * 这一页没有专门的接口——B 站不提供「推荐音乐人」的公开列表，逐条查
 * `space/acc/info` 又会吃风控（实测连查十四个，十二个返回 -352）。
 * 所以反过来做：**从推荐音乐池里聚合**。
 *
 * 谁在这个池子里出的歌多，谁就是这一期该被推荐的人。好处是零额外请求、
 * 天然贴合「这个应用是用来听歌的」，而且推荐 UP 主和推荐音乐永远同源，
 * 不会出现「推荐页放着一首新歌，推荐 UP 里却找不到这个人」。
 */
object RecommendApi {

    /**
     * 推荐 UP 主的一行。
     *
     * @property songs 这个人在池子里有几首。排序用它，界面也用它——
     *   「三首」比「一首」更能说明这个人值得点进去
     * @property sample 用来取封面/背景的那一首
     */
    data class UpEntry(
        val mid: Long,
        val name: String,
        val songs: Int,
        val cover: String,
        val sample: Track,
    )

    /** 一页最多列出多少人。再多人名就只是滚动条，不是推荐 */
    private const val UP_LIMIT = 24

    /** 拉关注列表时一页多少个。50 是接口肯给的上限附近，再大也只是把页数省下来 */
    private const val FOLLOW_PS = 50

    /** 最多翻几页。6 页 = 300 人；再多就不值得为一个角标翻下去了 */
    private const val FOLLOW_MAX_PAGES = 6

    /**
     * 从曲池聚合 UP 主。
     *
     * 只认有 mid 的条目：收藏夹、历史这些来源可能只给名字不给 id，
     * 而这一页的每一个动作（进主页、显示已关注）都要 mid 才成立。
     *
     * 排序是「曲数降序，同数按池子里的先后」。不按粉丝数——那个数要额外
     * 每个 UP 打一次接口，为了排个序打二十次请求，性价比反过来了。
     */
    fun ups(tracks: List<Track>, limit: Int = UP_LIMIT): List<UpEntry> {
        val grouped = LinkedHashMap<Long, MutableList<Track>>()
        for (t in tracks) {
            if (t.ownerMid <= 0 || t.owner.isEmpty()) continue
            grouped.getOrPut(t.ownerMid) { ArrayList() } += t
        }
        return grouped.entries
            .map { (mid, list) ->
                val head = list.first()
                UpEntry(
                    mid = mid,
                    name = head.owner,
                    songs = list.size,
                    cover = head.cover,
                    sample = head,
                )
            }
            .sortedWith(compareByDescending<UpEntry> { it.songs })
            .take(limit)
    }

    /**
     * 我关注的人（一个 mid 集合）。
     *
     * 本来该用 `x/relation?fids=` 批量查——一次就能回答「这二十几个人里
     * 我关注了哪几个」。实测这个接口在登录态下返回 **-400 请求错误**
     * （未登录时反而是 -101，所以不能靠「没报错」判断它可用），
     * 而逐个查 `space/acc/info` 会吃风控。剩下的只有一条稳的路：
     * 把关注列表拉下来，在内存里比对。
     *
     * 代价是「关注了三百人以上会有遗漏」，换来的是绝不风控、绝不逐条打请求。
     * 一页 50 个、最多 6 页，绝大多数账号第一页就翻完了。
     *
     * 未登录时直接给空集：界面上表现为「一个标记都不显示」，不该弹任何东西。
     */
    fun followedSet(log: (String) -> Unit = {}): Set<Long> {
        if (!LoginApi.isLoggedIn()) return emptySet()
        val now = System.currentTimeMillis()
        followedCache?.let { if (now - followedAt < FOLLOW_TTL) return it }

        val out = HashSet<Long>()
        for (page in 1..FOLLOW_MAX_PAGES) {
            val got = runCatching { UserApi.followings(page, log, FOLLOW_PS) }.getOrNull()
            if (got.isNullOrEmpty()) break
            got.forEach { out += it.mid }
            // 这一页没满，说明到底了
            if (got.size < FOLLOW_PS) break
        }
        // 一个人都没拿到（网络挂了）时不写进缓存，下次还能重试
        if (out.isNotEmpty()) {
            followedCache = out
            followedAt = now
        }
        log("关注集合 ${out.size} 人")
        return out
    }

    /** 关系统计缓存。一分钟：够翻几个来回，又不会让刚点的关注一直不生效 */
    private const val FOLLOW_TTL = 60_000L
    private var followedCache: Set<Long>? = null
    private var followedAt = 0L
}
