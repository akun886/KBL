package com.kunplayer.tv.net

import android.content.Context
import com.kunplayer.tv.cache.Favorites
import com.kunplayer.tv.model.Track

/**
 * 收藏状态。
 *
 * 收藏写在 B 站的云端收藏夹里，「我的收藏」那一页读的也是它——
 * 两边必须是同一份数据，否则会出现「点了收藏，去收藏页却看不到」，
 * 看起来就像收藏根本没生效。
 *
 * 状态查询没有专门接口（`fav/resource/ids` 只列夹内内容，不回答「在不在里面」），
 * 所以这里把每个夹子的内容拉下来，在内存里建一份索引：
 *
 *   bvid → 它在哪些夹子里
 *
 * 索引是懒加载的：进播放页第一次用到时才拉一次，之后一直有效。
 * 登录态下用它；未登录时退回本地收藏（本地收藏不需要联网，也不需要账号）。
 *
 * 写入一律走 `CloudFav.deal`：加收藏就加进默认夹，取消就从**所有**夹移出——
 * B 站的「已收藏」是「在任意一个夹里」，只从默认夹移出的话，
 * 这首歌还躺在「音乐」夹里，界面却显示未收藏，比不写还糟。
 */
object FavState {

    /** bvid → 它在哪些收藏夹里 */
    private val cloudIndex = HashMap<String, MutableSet<Long>>()

    /** 索引是否已经拉过 */
    @Volatile
    private var indexed = false

    /** 拉索引时的锁。同一时刻只允许一个请求在跑 */
    private val lock = Any()

    /** 默认收藏夹（attr=0 的那个）。B 站每个账号都有，且不可删除 */
    private var defaultFolderId = 0L

    /** 最近一次索引拉取失败的原因，用于提示用户 */
    @Volatile
    var lastError: String = ""
        private set

    /**
     * 确保索引可用。已加载过就直接返回。
     *
     * 必须在 IO 线程调用。
     */
    fun ensureIndexed(context: Context, log: (String) -> Unit = {}) {
        if (indexed) return
        synchronized(lock) {
            if (indexed) return
            if (!LoginApi.isLoggedIn()) {
                // 未登录：云端没有收藏可言，索引保持空，靠本地收藏兜底
                indexed = true
                return
            }
            try {
                val mid = LoginApi.mid()
                val folders = FavApi.folders(mid, log)
                // 默认收藏夹：B 站每个账号都有，名字固定是「默认收藏夹」，且不能删。
                // 先按名字认，再退回 attr==0——attr 只是一般意义的「正常」，不专指默认夹
                defaultFolderId = folders.firstOrNull { it.title.contains("默认") }?.id
                    ?: folders.firstOrNull { it.attr == 0 }?.id
                    ?: folders.firstOrNull()?.id
                    ?: 0L

                val found = HashMap<String, MutableSet<Long>>()
                for (folder in folders.take(FavApi.MAX_SCAN)) {
                    val medias = try {
                        FavApi.resources(folder.id, log = log)
                    } catch (t: Throwable) {
                        log("收藏夹《${folder.title}》拉取失败：${t.message}")
                        continue
                    }
                    medias.forEach { media ->
                        if (media.bvid.isEmpty()) return@forEach
                        found.getOrPut(media.bvid) { HashSet() } += folder.id
                    }
                }
                cloudIndex.clear()
                cloudIndex.putAll(found)
                indexed = true
                lastError = ""
                log("收藏索引：${found.size} 首，默认夹 id=$defaultFolderId")
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                log("收藏索引拉取失败：$lastError")
                // 不标记为已加载：下次还会重试
            }
        }
    }

    /** 强制下次重新拉索引（收藏写完之后调用，让状态立刻一致） */
    fun invalidate() {
        indexed = false
    }

    /**
     * 这首歌是不是已收藏。
     *
     * 登录态下以云端为准：本地那份只是镜像，它可能过期（云端被别人删了、
     * 或者换了账号）。索引还没拉完时先用镜像顶上，等索引到了再以云端为准。
     *
     * @param indexedNow 索引是否已经在内存里。为 false 时只能靠本地收藏判断。
     */
    fun isFavorited(context: Context, bvid: String, indexedNow: Boolean = true): Boolean {
        if (bvid.isEmpty()) return false
        if (LoginApi.isLoggedIn()) {
            if (!indexedNow || !indexed) return Favorites.contains(context, bvid)
            return cloudIndex.containsKey(bvid)
        }
        return Favorites.contains(context, bvid)
    }

    /**
     * 收藏 / 取消收藏。
     *
     * @return true 表示这次是「加入收藏」
     */
    fun toggle(context: Context, track: Track, log: (String) -> Unit = {}): Boolean {
        if (track.bvid.isEmpty()) return false

        // 没登录：只能存本地。收藏这件事不该以「先注册」为前提
        if (!LoginApi.isLoggedIn()) {
            val added = Favorites.toggle(context, track)
            log(if (added) "本地收藏" else "本地取消收藏")
            return added
        }

        // 写之前必须知道它此刻在哪些夹里：没有索引就分不清「加」还是「移」，
        // 盲目往默认夹里加，结果是同一首歌在两个夹里，取消收藏也删不干净。
        ensureIndexed(context, log)

        val folders = cloudIndex[track.bvid]
        if (folders != null && folders.isNotEmpty()) {
            // 从**所有**夹移出：只要还剩一个夹，这首歌在 B 站那边仍算已收藏
            CloudFav.deal(track, delIds = folders.toList(), log = log)
            cloudIndex.remove(track.bvid)
            Favorites.set(context, track, false)
            log("云端取消收藏（移出 ${folders.size} 个夹）")
            return false
        }

        CloudFav.deal(track, addIds = listOf(defaultFolderId), log = log)
        cloudIndex.getOrPut(track.bvid) { HashSet() } += defaultFolderId
        Favorites.set(context, track, true)
        log("云端收藏")
        return true
    }

    /** 默认收藏夹 id。没登录或没拉过索引时是 0 */
    fun defaultFolder(): Long = defaultFolderId
}
