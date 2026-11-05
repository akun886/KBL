package com.kunplayer.tv.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 用户关系与 UP 主主页的返回结构。
 *
 * 字段一律可空 + 默认值：这些接口在「未关注 / 已关注 / 私密空间 / 风控」
 * 四种情况下的字段组合完全不同（关注列表里 mid 是数字、资料页里也是，
 * 但 attribute 的含义会随关系类型变），严格模型只会把能用的响应判死。
 */

// ---------------------------------------------------------------- 关注列表

@Serializable
data class FollowingsData(
    val list: List<FollowUser>? = null,
    val total: Int = 0,
    @SerialName("re_version") val reVersion: Int = 0,
)

@Serializable
data class FollowUser(
    val mid: Long = 0,
    val uname: String = "",
    val face: String = "",
    val sign: String = "",
    /** 0 正常；2 是「特别关注」，1 是「已关注」等状态位 */
    val attribute: Int = 0,
    @SerialName("official_verify") val officialVerify: OfficialVerify? = null,
)

@Serializable
data class OfficialVerify(
    val type: Int = -1,
    val desc: String = "",
)

// ------------------------------------------------------------ UP 主主页

/**
 * UP 主资料。
 *
 * 全部字段都用 JsonElement 收：这个接口在不同账号、不同状态下给的类型不一样
 * ——`level` 有时是数字有时是对象，`is_followed` 有时是 0/1 有时是布尔，
 * `official` / `pendant` 的子字段也各有各的写法。
 * 写死一种类型就会在另一种上直接解析失败，而失败的代价是「整页打不开」。
 */
@Serializable
data class SpaceUser(
    val mid: Long = 0,
    val name: String = "",
    val face: String = "",
    val sign: String = "",
    @SerialName("is_followed") val isFollowedRaw: JsonElement = JsonNull,
    @SerialName("level") val levelRaw: JsonElement = JsonNull,
    @SerialName("official") val officialRaw: JsonElement = JsonNull,
    @SerialName("pendant") val pendantRaw: JsonElement = JsonNull,
) {
    /** 我是否关注了这个人 */
    val isFollowed: Boolean
        get() = when (val v = isFollowedRaw) {
            is JsonPrimitive -> v.booleanOrNull ?: ((v.intOrNull ?: 0) != 0)
            else -> false
        }

    /** 等级。0 = 没给 */
    val level: Int
        get() = when (val v = levelRaw) {
            is JsonPrimitive -> v.intOrNull ?: 0
            is JsonObject -> (v["current_level"] as? JsonPrimitive)?.intOrNull ?: 0
            else -> 0
        }

    /** 认证信息里的头衔（官方认证 UP 才有） */
    val officialTitle: String
        get() {
            val obj = officialRaw as? JsonObject ?: return ""
            return (obj["title"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        }
}

@Serializable
data class ArcSearchData(
    val list: ArcList? = null,
    val page: ArcPage? = null,
)

@Serializable
data class ArcList(
    val vlist: List<ArcItem>? = null,
)

@Serializable
data class ArcPage(
    val count: Int = 0,
    val pn: Int = 1,
    val ps: Int = 30,
)

@Serializable
data class ArcItem(
    val aid: Long = 0,
    val bvid: String = "",
    val title: String = "",
    val pic: String = "",
    val author: String = "",
    val mid: Long = 0,
    /** "165:23" */
    val length: String = "",
    val created: Long = 0,
    val play: Int = 0,
    /** 多 P 稿件的合集 id。单 P 时为 0 */
    @SerialName("season_id") val seasonId: Long = 0,
)

/** x/player/pagelist 的一条：分 P 表 */
@Serializable
data class PageItem(
    val cid: Long = 0,
    val page: Int = 0,
    val part: String = "",
    val duration: Int = 0,
)
