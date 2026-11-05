package com.kunplayer.tv.net

import java.net.URLEncoder
import java.security.MessageDigest

/**
 * WBI 签名。
 *
 * mixinKeyEncTab 与算法来自 aaa1115910/bv（MIT）与 wood3n/biu 的实现，逐字照抄，
 * 见仓库根目录 THIRD-PARTY-NOTICES.md。不自己「重写」这个表的原因是：错一位就会
 * 全站 412，而 412 和风控的表现完全一样，排查成本极高。
 */
object Wbi {

    private val mixinKeyEncTab = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
        26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
        20, 34, 44, 52
    )

    @Volatile
    private var imgKey: String? = null

    @Volatile
    private var subKey: String? = null

    fun ready(): Boolean = imgKey != null && subKey != null

    /** 从 nav 的 wbi_img.img_url / sub_url 里取文件名（去扩展名） */
    fun updateFromUrls(imgUrl: String, subUrl: String) {
        if (imgUrl.isBlank() || subUrl.isBlank()) return
        imgKey = imgUrl.substringAfterLast('/').substringBeforeLast('.')
        subKey = subUrl.substringAfterLast('/').substringBeforeLast('.')
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun mixinKey(): String {
        val orig = (imgKey ?: "") + (subKey ?: "")
        require(orig.length >= 64) { "WBI key 未就绪" }
        return mixinKeyEncTab.fold(StringBuilder()) { acc, i -> acc.append(orig[i]) }
            .substring(0, 32)
    }

    private fun enc(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /**
     * @param params 未编码的原始参数
     * @return 已带 wts 与 w_rid 的完整 query string
     */
    fun sign(params: Map<String, String>): String {
        val wts = (System.currentTimeMillis() / 1000).toString()
        val sorted = (params + ("wts" to wts)).toSortedMap()
        val query = sorted.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val wRid = md5(query + mixinKey())
        return "$query&w_rid=$wRid"
    }
}

class BiliException(message: String) : Exception(message)
