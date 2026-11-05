package com.kunplayer.tv.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 分区最新（x/web-interface/newlist）的条目结构。
 * 与排行榜（ranking/v2）是同一套字段的超集，所以一个 RankItem 就够，缺的字段有默认值兜底。
 */
@Serializable
data class NewListResp(
    val code: Int = -1,
    val message: String = "",
    val data: NewListData? = null,
)

@Serializable
data class NewListData(
    val archives: List<RankItem> = emptyList(),
)

@Serializable
data class RankItem(
    val aid: Long = 0,
    val bvid: String = "",
    val cid: Long = 0,
    val pic: String = "",
    val title: String = "",
    val duration: Int = 0,
    val owner: UpOwner? = null,
    /** 分区名。排行榜 97 条里带得很全，是首页切行的唯一依据。 */
    val tname: String = "",
    val tid: Int = 0,
    /** 分 P 数。>1 是一张合集，列表里要标出来 */
    val videos: Int = 0,
)

@Serializable
data class UpOwner(
    val mid: Long = 0,
    val name: String = "",
    /** 头像。收藏夹与历史接口给，排行榜不给 */
    val face: String = "",
)

/**
 * 音乐区排行榜（x/web-interface/ranking/v2?rid=3）。首页的「推荐音乐」用它。
 *
 * 和 `newlist` 一样返回完整的稿件结构，所以带 `tname`——这正是它比
 * `region/feed/rcmd` 值钱的地方：推荐流给的是排好序的一条流，没有分区名，
 * 想按「这是不是歌」筛一遍就只能靠猜标题；排行榜每条都自己报分区。
 */
@Serializable
data class RankData(
    val list: List<RankItem> = emptyList(),
)

/**
 * 分区推荐流（x/web-interface/region/feed/rcmd）。首页的「音乐」用它。
 *
 * 与排行榜最大的区别：**不返回 tname**，所以这一路数据没法再按分类分节；
 * 它给的是一条已经排好序的推荐流，顺序本身就是信息。
 */
@Serializable
data class RegionFeedResp(
    val code: Int = -1,
    val message: String = "",
    val data: RegionFeedData? = null,
)

@Serializable
data class RegionFeedData(
    val archives: List<RegionArchive> = emptyList(),
)

@Serializable
data class RegionArchive(
    val aid: Long = 0,
    val bvid: String = "",
    val cid: Long = 0,
    val title: String = "",
    val cover: String = "",
    val duration: Int = 0,
    val author: UpOwner? = null,
)

/**
 * 音乐综合榜（x/centralization/interface/music/comprehensive/web/rank）。首页的「流行」用它。
 *
 * 这个接口有两层：顶层是「音乐条目」（music_id / album / 网易云式的曲库元数据），
 * 里面嵌的 related_archive 才是可播放的那条 B 站稿件。
 * 两层的 aid / cid 都是**字符串**，直接当数字解析会炸。
 */
@Serializable
data class PopRankResp(
    val code: Int = -1,
    val message: String = "",
    val data: PopRankData? = null,
)

@Serializable
data class PopRankData(
    val list: List<PopRankItem> = emptyList(),
)

@Serializable
data class PopRankItem(
    val bvid: String = "",
    val cid: String = "",
    val cover: String = "",
    @SerialName("music_title") val musicTitle: String = "",
    val author: String = "",
    @SerialName("related_archive") val relatedArchive: PopArchive? = null,
)

@Serializable
data class PopArchive(
    val aid: String = "",
    val bvid: String = "",
    val cid: String = "",
    val cover: String = "",
    val title: String = "",
    val username: String = "",
    /** 作者 mid。字符串型，和顶层那两个一样 */
    val uid: String = "",
    val duration: Int = 0,
)
