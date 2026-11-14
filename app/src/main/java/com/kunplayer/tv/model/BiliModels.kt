package com.kunplayer.tv.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * B 站通用响应外壳。data 允许为 null——未登录（code=-101）时 nav 依然会带 wbi_img，
 * 这种情况必须能解析出来，不能因为 code != 0 就整条丢弃。
 */
@Serializable
data class ApiResp<T>(
    val code: Int = -1,
    val message: String = "",
    val data: T? = null,
)

@Serializable
data class NavData(
    @SerialName("isLogin") val isLogin: Boolean = false,
    val uname: String = "",
    val mid: Long = 0,
    /** 头像。「我的」页要用它，别的地方用不到 */
    val face: String = "",
    val vipStatus: Int = 0,
    /**
     * 会员身份。`label.text` 就是 B 站自己那套称呼（「大会员」「年度大会员」），
     * 比按 status 拼一个词可靠——年度大会员和普通大会员在 status 上都是 1。
     * 未登录时为 null，这里必须可空：字段缺失不该让整条 nav 解析失败。
     */
    val vip: VipInfo? = null,
    @SerialName("wbi_img") val wbiImg: WbiImg? = null,
)

@Serializable
data class VipInfo(
    val type: Int = 0,
    val status: Int = 0,
    @SerialName("due_date") val dueDate: Long = 0,
    val label: VipLabel? = null,
)

@Serializable
data class VipLabel(
    val text: String = "",
    @SerialName("label_theme") val labelTheme: String = "",
)

@Serializable
data class WbiImg(
    @SerialName("img_url") val imgUrl: String = "",
    @SerialName("sub_url") val subUrl: String = "",
)

@Serializable
data class FingerData(
    @SerialName("b_3") val b3: String = "",
    @SerialName("b_4") val b4: String = "",
)

/**
 * 稿件详情。`pages` 是多 P 稿件的分 P 表：一项一个 P，`part` 就是那一 P 的歌名。
 *
 * 合集名（稿件标题）在 `title`，`videos` 是 P 数，与 `pages.size` 同值——
 * 前者是接口声明的数量，后者是实际下发的列表，用 pages.size 更实在。
 */
@Serializable
data class ViewData(
    val aid: Long = 0,
    val bvid: String = "",
    val cid: Long = 0,
    val title: String = "",
    val duration: Int = 0,
    val videos: Int = 0,
    val pic: String = "",
    val owner: UpOwner? = null,
    val pages: List<Page> = emptyList(),
) {
    @Serializable
    data class Page(
        val cid: Long = 0,
        val page: Int = 0,
        val part: String = "",
        val duration: Int = 0,
    )
}

@Serializable
data class PlayUrlData(
    val quality: Int = 0,
    val format: String = "",
    @SerialName("timelength") val timeLength: Int = 0,
    val dash: Dash? = null,
)

@Serializable
data class Dash(
    val duration: Int = 0,
    val audio: List<DashData>? = null,
    val dolby: Dolby? = null,
    val flac: Flac? = null,
)

@Serializable
data class Dolby(
    val audio: List<DashData>? = null,
    val type: Int = 2,
)

@Serializable
data class Flac(
    val display: Boolean = false,
    val audio: DashData? = null,
)

/**
 * 与 bv 的 DashData 相比这里改了两处，都是必须打的补丁：
 *   1. backupUrl 补 @SerialName("backup_url")——原实现少了它，备份 CDN 永远为空；
 *   2. width/height/frameRate/sar 给默认值——音频条目这几个字段可能缺失，
 *      缺一个就整条反序列化失败（kotlinx 对非空无默认值字段是硬要求）。
 */
@Serializable
data class DashData(
    val id: Int = 0,
    @SerialName("base_url") val baseUrl: String = "",
    @SerialName("backup_url") val backupUrl: List<String> = emptyList(),
    val bandwidth: Int = 0,
    @SerialName("mime_type") val mimeType: String = "",
    val codecs: String = "",
    val width: Int = 0,
    val height: Int = 0,
    @SerialName("frame_rate") val frameRate: String = "",
    val sar: String = "",
    @SerialName("start_with_sap") val startWithSap: Int = 0,
)

/** 选中的音轨，url 优先，backupUrls 留给失败切换 */
data class AudioPick(
    val kind: String,
    val url: String,
    val backupUrls: List<String>,
    val detail: String,
)
