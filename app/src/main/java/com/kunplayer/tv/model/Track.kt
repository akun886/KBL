package com.kunplayer.tv.model

import kotlinx.serialization.Serializable

/**
 * 应用自己的曲目模型，也是首屏缓存的落盘格式。
 *
 * 刻意与接口返回结构解耦：接口字段会变（ranking 与 newlist 都能当来源），
 * 但「一条可播放的曲目」永远只需要这六项。cid 一起存下来，
 * 播放时就不用再为每首歌打一次 view 接口——排行榜本来就返回 cid。
 */
@Serializable
data class Track(
    val bvid: String = "",
    /** 打分用的 aid。排行榜/搜索/收藏/历史都直接给，有心跳（历史记录）才能落库，缺它就得每次多打一次 view */
    val aid: Long = 0L,
    val cid: Long = 0L,
    val title: String = "",
    val cover: String = "",
    val owner: String = "",

    /**
     * 作者的 mid（用户 id）。
     *
     * 光有作者名字是不够的：点作者要跳到他的主页去，主页是按 mid 查的。
     * 名字会重名、也可能被改，mid 不会。0 = 这个来源没给（那就只能显示名字，
     * 点不动）。
     */
    val ownerMid: Long = 0L,
    val durationSec: Int = 0,
    /** 接口里的 tname（翻唱 / 演奏 / 音乐现场…），首页按它切行。旧缓存没有这个字段，默认空串。 */
    val category: String = "",

    /**
     * 所属合集（多 P 稿件）的名字，空 = 单曲。
     *
     * 一首歌在这个应用里有两种身份：自己是稿件，或者只是别人一百个 P 里的一个。
     * 界面上必须能分开——否则整张合集会显示成「只有这一首」，用户按下一首
     * 会发现直接跳到了另一个 UP 主。
     */
    val album: String = "",

    /** 这个稿件一共几个 P。0/1 表示单曲，>1 才是合集。旧缓存没有，按 0 处理 */
    val pages: Int = 0,

    /** 当前是第几个 P（从 1 起）。单曲为 0 */
    val pageNo: Int = 0,
) {
    /** 看得到曲目列表的前提：确实是合集，且已经解析出每一 P */
    val isCollection: Boolean get() = pages > 1

    val playable: Boolean get() = bvid.isNotEmpty() && cid > 0

    /** 卡片角标用：4:31 / 1:02:33 */
    val durationText: String
        get() {
            if (durationSec <= 0) return ""
            val h = durationSec / 3600
            val m = durationSec % 3600 / 60
            val s = durationSec % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
}
