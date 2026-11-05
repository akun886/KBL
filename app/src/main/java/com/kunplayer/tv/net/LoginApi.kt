package com.kunplayer.tv.net

import com.kunplayer.tv.model.QrGen
import com.kunplayer.tv.model.QrGenResp
import com.kunplayer.tv.model.QrPoll
import com.kunplayer.tv.model.QrPollResp

/**
 * 扫码登录（Web 扫码，不是 TV 端 appkey 登录）。
 *
 * 选 Web 扫码而不是 `passport-tv-login`：TV 那套要 appkey/appsec 做 HMAC 签名，
 * 且拿到的是 TV 端 access_token，和网页 Cookie 不是一套；而后续收藏、历史、心跳、
 * FLAC 音轨全按 Web Cookie（SESSDATA + bili_jct）走。为了一张二维码多维护一套
 * 签名与 token 刷新，不值得。
 *
 * 流程三步：
 *   1. generate → 得到 qrcode_key 与一段 https 链接，把链接画成二维码；
 *   2. poll（1.5~2 秒一次）→ 86101 未扫 / 86090 已扫待确认 / 86038 过期 / 0 成功；
 *   3. 成功时 data.url 是一条 crossDomain 链，SESSDATA 等 Cookie 全在它的 query 里，
 *      把它们抠出来存进 CookieStore，之后所有请求自带登录态。
 */
object LoginApi {

    private const val PASSPORT = "https://passport.bilibili.com"

    /** poll 的 data.code */
    const val OK = 0
    const val WAIT_SCAN = 86101
    const val WAIT_CONFIRM = 86090
    const val EXPIRED = 86038

    private val authCookies = listOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid")

    fun isLoggedIn(): Boolean = !CookieStore.get("SESSDATA").isNullOrEmpty()

    fun mid(): Long = CookieStore.get("DedeUserID")?.toLongOrNull() ?: 0L

    /** 心跳与所有写接口都要它，缺失时服务端返回 -111 */
    fun csrf(): String = CookieStore.get("bili_jct").orEmpty()

    fun qrGenerate(): QrGen {
        val r = BiliHttp.get("$PASSPORT/x/passport-login/web/qrcode/generate?source=main-fe-url")
        if (!r.isOk) throw BiliException("二维码生成失败：HTTP ${r.code} ${r.preview}")
        val resp = r.body.decodeApi<QrGenResp>("qrcode/generate")
        val data = resp.data ?: throw BiliException("二维码生成无 data：${r.preview}")
        if (data.url.isEmpty() || data.qrcodeKey.isEmpty()) {
            throw BiliException("二维码字段为空：${r.preview}")
        }
        return data
    }

    /** poll 的产物：状态 + 随响应头一起下发的 Cookie（真机上登录态主要在这里） */
    class PollOutcome(val poll: QrPoll, val setCookies: List<String>)

    fun qrPoll(qrcodeKey: String): PollOutcome {
        val r = BiliHttp.get(
            "$PASSPORT/x/passport-login/web/qrcode/poll?qrcode_key=$qrcodeKey&source=main-fe-url"
        )
        if (!r.isOk) throw BiliException("扫码状态查询失败：HTTP ${r.code} ${r.preview}")
        val resp = r.body.decodeApi<QrPollResp>("qrcode/poll")
        val data = resp.data ?: throw BiliException("扫码状态无 data（code=${resp.code}）：${r.preview}")
        return PollOutcome(data, r.setCookies)
    }

    /**
     * 把 crossDomain 链接里的 Cookie 抠出来。
     *
     * 刻意手工切 query 而不是用 Uri.getQueryParameter：后者会做一次 URL 解码，
     * 而 SESSDATA 的原始值本身就带 %2C（逗号），解码后再拼回 Cookie 头会变样。
     * 浏览器 Cookie 罐里存的就是未解码的那串，照抄才最稳。
     */
    fun applyCookies(
        crossDomainUrl: String,
        setCookies: List<String> = emptyList(),
        log: (String) -> Unit = {},
    ): Boolean {
        val found = LinkedHashSet<String>()

        // 第一条路：crossDomain 跳转链的 query
        for (name in authCookies) {
            val value = rawQueryParam(crossDomainUrl, name)
            if (!value.isNullOrEmpty()) {
                CookieStore.put(name, value)
                found += name
            }
        }

        // 第二条路：poll 响应的 Set-Cookie。实测这台设备上 SESSDATA 主要走这条，
        // data.url 里的参数并不总是全的，只认 url 会得到「扫完没反应」。
        for (raw in setCookies) {
            val pair = raw.substringBefore(';').trim()
            val name = pair.substringBefore('=', "")
            val value = pair.substringAfter('=', "")
            if (name.isEmpty() || value.isEmpty()) continue
            if (name !in authCookies) continue
            CookieStore.put(name, value)
            found += name
        }

        log("  登录 Cookie 落地：${if (found.isEmpty()) "无" else found.joinToString()}（url 长度 ${crossDomainUrl.length}，Set-Cookie ${setCookies.size} 条）")
        return isLoggedIn()
    }

    private fun rawQueryParam(url: String, name: String): String? {
        val query = url.substringAfter('?', "")
        if (query.isEmpty()) return null
        val hit = query.split('&').firstOrNull { it.startsWith("$name=") } ?: return null
        return hit.substringAfter('=').takeIf { it.isNotEmpty() }
    }

    /**
     * 退出只清鉴权 Cookie，不碰 buvid3/buvid4。
     *
     * 设备指纹每变一次就等于是「一台新设备」，而这正是风控最敏感的信号；
     * 退出登录没有理由顺手把指纹也换了。
     */
    fun logout() {
        authCookies.forEach { CookieStore.remove(it) }
    }
}
