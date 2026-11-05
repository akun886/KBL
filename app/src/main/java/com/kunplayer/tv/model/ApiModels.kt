package com.kunplayer.tv.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phase 4 接口层的返回结构：扫码登录、云端收藏、搜索、历史。
 *
 * 一律用可空 data + 默认值：这些接口在未登录、空结果、风控三种情况下返回的
 * 字段组合完全不同（data 为 null、medias 缺字段、result 少一半键都见过），
 * 严格模型只会让「本来能用」的响应解析失败。
 */

// ---------------------------------------------------------------- 扫码登录

@Serializable
data class QrGenResp(
    val code: Int = -1,
    val message: String = "",
    val data: QrGen? = null,
)

@Serializable
data class QrGen(
    /** 二维码内容。必须原样编码进图形，不能改写、不能缩短 */
    val url: String = "",
    @SerialName("qrcode_key") val qrcodeKey: String = "",
)

@Serializable
data class QrPollResp(
    val code: Int = -1,
    val message: String = "",
    val data: QrPoll? = null,
)

/**
 * 注意 data.code 与外壳 code 是两回事：外壳 code=0 只代表「请求成功」，
 * 真正的登录状态在 data.code 里（0 成功 / 86101 未扫码 / 86090 已扫码待确认 / 86038 已失效）。
 */
@Serializable
data class QrPoll(
    /** 成功时是一条 crossDomain 跳转链，SESSDATA 等 Cookie 全在它的 query 里 */
    val url: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    val timestamp: Long = 0,
    val code: Int = -1,
    val message: String = "",
)

// ---------------------------------------------------------------- 云端收藏

@Serializable
data class FavFolderResp(
    val code: Int = -1,
    val message: String = "",
    val data: FavFolderData? = null,
)

@Serializable
data class FavFolderData(
    /** 建夹接口返回的就是这个对象：新夹子的 id。列表接口返回的是 count + list */
    val id: Long = 0,
    val count: Int = 0,
    val list: List<FavFolder>? = null,
)

@Serializable
data class FavFolder(
    val id: Long = 0,
    val fid: Long = 0,
    val title: String = "",
    @SerialName("media_count") val mediaCount: Int = 0,
    /** 0 正常；非 0 是私密/已删除，取资源列表会空 */
    val attr: Int = 0,
)

@Serializable
data class FavListResp(
    val code: Int = -1,
    val message: String = "",
    val data: FavListData? = null,
)

@Serializable
data class FavListData(
    val info: FavInfo? = null,
    /** 收藏夹为空时这个键直接不存在，所以必须可空 */
    val medias: List<FavMedia>? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class FavInfo(
    val id: Long = 0,
    val title: String = "",
    @SerialName("media_count") val mediaCount: Int = 0,
)

@Serializable
data class FavMedia(
    /** 视频的 aid，心跳要用 */
    val id: Long = 0,
    val bvid: String = "",
    val title: String = "",
    val cover: String = "",
    val duration: Int = 0,
    val upper: UpOwner? = null,
    /** 0 = 正常；1/2/9 等表示失效（已删除/私有/失效稿件），播放会 404 */
    val attr: Int = 0,
)

// ------------------------------------------------------------------- 搜索

@Serializable
data class SearchResp(
    val code: Int = -1,
    val message: String = "",
    val data: SearchData? = null,
)

@Serializable
data class SearchData(
    /** 无结果时整个键缺失 */
    val result: List<SearchItem>? = null,
    val numResults: Int = 0,
    val numPages: Int = 0,
)

@Serializable
data class SearchItem(
    val type: String = "",
    /** 视频的 aid；部分条目只有 id 没有 aid */
    val id: Long = 0,
    val aid: Long = 0,
    val bvid: String = "",
    /** 带 <em class="keyword"> 高亮标签，展示前必须剥掉 */
    val title: String = "",
    /** 以 // 开头，没有协议 */
    val pic: String = "",
    val author: String = "",
    /** "4:38"；超过一小时的会是 "419:2" 这种分钟数溢出的写法 */
    val duration: String = "",
    /** 作者 mid。点作者名进主页要靠它 */
    val mid: Long = 0,
    /** 作者头像 */
    val upic: String = "",
    /**
     * 二级分区名，例如「乐评盘点」「翻唱」。
     *
     * 搜索结果里叫 typename，排行榜里叫 tname——同一个东西两个字段名。
     * 长合集（一小时以上的歌单）基本全在「乐评盘点」这一格，所以推荐长合集
     * 这条路反过来要它，而不是像单曲池那样把它挡掉。
     */
    val typename: String = "",
)

// ------------------------------------------------------------------- 历史

@Serializable
data class HistoryResp(
    val code: Int = -1,
    val message: String = "",
    val data: HistoryData? = null,
)

@Serializable
data class HistoryData(
    val list: List<HistoryItem>? = null,
    val cursor: HistoryCursor? = null,
)

@Serializable
data class HistoryCursor(
    val max: Long = 0,
    @SerialName("view_at") val viewAt: Long = 0,
    val business: String = "",
)

@Serializable
data class HistoryItem(
    val title: String = "",
    /** 多 P 视频里这一 P 的名字，通常就是歌名 */
    @SerialName("show_title") val showTitle: String = "",
    val cover: String = "",
    val duration: Int = 0,
    @SerialName("author_name") val authorName: String = "",
    /** 作者 mid。点作者名进主页要靠它 */
    @SerialName("author_mid") val authorMid: Long = 0L,
    /** 作者头像 */
    @SerialName("author_face") val authorFace: String = "",
    /** 是否已收藏。历史接口直接给，不用再问收藏夹 */
    @SerialName("is_fav") val isFav: Int = 0,
    @SerialName("view_at") val viewAt: Long = 0,
    /** 这个稿件有几个 P。>1 说明听的是某张合集里的一首 */
    val videos: Int = 0,
    val history: HistoryMeta? = null,
)

@Serializable
data class HistoryMeta(
    /** 视频的 aid */
    val oid: Long = 0,
    val bvid: String = "",
    /** 历史接口自带 cid，不用再打 view */
    val cid: Long = 0,
    val business: String = "",
    val page: Int = 0,
)
