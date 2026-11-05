package com.kunplayer.tv.net

import com.kunplayer.tv.model.ApiResp
import com.kunplayer.tv.model.FavFolderResp
import com.kunplayer.tv.model.Track

/**
 * 云端收藏夹的写操作。
 *
 * 读在 [FavApi]（只读的列表与内容），写在这里。三件事：
 * 建夹、删夹、把稿件放进/拿出某个夹子。
 *
 * 三个接口的参数名都和直觉不一样，写错一个就是 -400：
 *
 *   · 建夹 `x/v3/fav/folder/add`     —— 不是 `create`（那个路径 404）
 *   · 删夹 `x/v3/fav/folder/del`     —— 参数是 **media_ids**（复数、逗号分隔），
 *                                       传 `media_id` 一律 -400「请求错误」
 *   · 内容 `x/v3/fav/resource/deal`   —— rid 是 **aid**，type 固定 2（视频稿件）
 *
 * 全部走 POST 且必须带 csrf（bili_jct），否则 -111。缺 csrf 时接口返回的是
 * 「CSRF 校验失败」而不是「请登录」，容易误判成 Cookie 过期。
 */
object CloudFav {

    private const val BASE = "https://api.bilibili.com"

    /** 视频稿件。B 站资源类型：2=视频、12=音频、21=视频合集 */
    private const val TYPE_VIDEO = 2

    /** 建夹结果 */
    class Created(val id: Long, val title: String)

    /**
     * 新建一个收藏夹。
     *
     * privacy=0 是公开，1 是私密。默认给公开：电视上没人会去改这个，
     * 私密夹在别人机器上看不到，等于白建。
     */
    fun create(title: String, log: (String) -> Unit = {}): Created {
        val name = title.trim()
        if (name.isEmpty()) throw BiliException("收藏夹名字为空")
        if (name.length > 20) throw BiliException("收藏夹名字太长")
        val csrf = LoginApi.csrf()
        if (csrf.isEmpty()) throw BiliException("未登录，无法新建收藏夹")

        val r = BiliHttp.postForm(
            "$BASE/x/v3/fav/folder/add",
            mapOf(
                "title" to name,
                "intro" to "",
                "privacy" to "0",
                "type" to "2",
                "csrf" to csrf,
            ),
        )
        log("建夹 HTTP ${r.code}")
        if (!r.isOk) throw BiliException("建夹 HTTP ${r.code} ${r.preview}")
        val resp = r.body.decodeApi<FavFolderResp>("fav/folder/add")
        if (resp.code != 0) throw BiliException("建夹 code=${resp.code} ${resp.message}")
        val id = resp.data?.id ?: 0L
        if (id <= 0) throw BiliException("建夹没返回 id")
        log("已建收藏夹《${name}》 id=$id")
        return Created(id, name)
    }

    /**
     * 删除收藏夹。
     *
     * 参数名是 `media_ids`（复数），这是唯一一个「照着文档写反而写错」的地方：
     * 传 `media_id` 服务端不报「参数缺失」，只回一句 -400「请求错误」，
     * 看起来像权限不够，其实是名字不对。
     */
    fun delete(mediaIds: List<Long>, log: (String) -> Unit = {}): Boolean {
        if (mediaIds.isEmpty()) return true
        val csrf = LoginApi.csrf()
        if (csrf.isEmpty()) throw BiliException("未登录，无法删除收藏夹")

        val r = BiliHttp.postForm(
            "$BASE/x/v3/fav/folder/del",
            mapOf(
                "media_ids" to mediaIds.joinToString(","),
                "csrf" to csrf,
            ),
        )
        log("删夹 HTTP ${r.code}")
        if (!r.isOk) throw BiliException("删夹 HTTP ${r.code} ${r.preview}")
        val resp = r.body.decodeApi<ApiResp<Int>>("fav/folder/del")
        if (resp.code != 0) throw BiliException("删夹 code=${resp.code} ${resp.message}")
        log("已删除 ${mediaIds.size} 个收藏夹")
        return true
    }

    /**
     * 把稿件放进某个夹子 / 从某个夹子拿出来。
     *
     * @param addIds 要加入的夹子 id
     * @param delIds 要移出的夹子 id
     */
    fun deal(
        track: Track,
        addIds: List<Long> = emptyList(),
        delIds: List<Long> = emptyList(),
        log: (String) -> Unit = {},
    ): Boolean {
        if (addIds.isEmpty() && delIds.isEmpty()) return true
        if (track.aid <= 0) throw BiliException("这一首没有 aid，无法收藏到云端")
        val csrf = LoginApi.csrf()
        if (csrf.isEmpty()) throw BiliException("未登录，无法收藏")

        val r = BiliHttp.postForm(
            "$BASE/x/v3/fav/resource/deal",
            mapOf(
                "rid" to track.aid.toString(),
                "type" to TYPE_VIDEO.toString(),
                "add_media_ids" to addIds.joinToString(","),
                "del_media_ids" to delIds.joinToString(","),
                "platform" to "web",
                "csrf" to csrf,
            ),
        )
        log("收藏写入 HTTP ${r.code}")
        if (!r.isOk) throw BiliException("收藏 HTTP ${r.code} ${r.preview}")
        val resp = r.body.decodeApi<ApiResp<FavDealData>>("fav/resource/deal")
        if (resp.code != 0) throw BiliException("收藏 code=${resp.code} ${resp.message}")
        return true
    }
}

/** fav/resource/deal 的 data。字段大半用不上，但 prompt 值得看一眼：它表示「是否弹了提示」 */
@kotlinx.serialization.Serializable
data class FavDealData(
    val prompt: Boolean = false,
    @kotlinx.serialization.SerialName("success_num") val successNum: Int = 0,
)
