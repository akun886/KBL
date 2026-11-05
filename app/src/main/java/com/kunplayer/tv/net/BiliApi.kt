package com.kunplayer.tv.net

import com.kunplayer.tv.model.ApiResp
import com.kunplayer.tv.model.AudioPick
import com.kunplayer.tv.model.DashData
import com.kunplayer.tv.model.FingerData
import com.kunplayer.tv.model.NavData
import com.kunplayer.tv.model.PlayUrlData
import com.kunplayer.tv.model.ViewData
import kotlinx.serialization.json.Json

/**
 * 探针用到的四个接口。刻意不引入 bili-api 的整套依赖（Ktor + Koin + gRPC + Jsoup），
 * 因为探针只需要 Web 端的三四个 GET，手写反而更快、更容易看清每一步失败在哪。
 *
 * 如果探针通过，第二阶段再决定是引入 bili-api 还是继续手写——那是独立决策。
 */
object BiliApi {

    private const val BASE = "https://api.bilibili.com"

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private inline fun <reified T> decode(body: String, what: String): T =
        try {
            json.decodeFromString<T>(body)
        } catch (e: Exception) {
            throw BiliException(
                "$what 解析失败（${e.javaClass.simpleName}）。原文前 160 字：${body.take(160)}"
            )
        }

    // ---------------------------------------------------------------- 1. buvid

    /**
     * 激活 buvid3 / buvid4。新设备首批请求被 412 卡住，多半是因为缺这一对指纹。
     */
    fun activateBuvid(log: (String) -> Unit): FingerData {
        val r = BiliHttp.get("$BASE/x/frontend/finger/spi", withCookie = false)
        log("  HTTP ${r.code}")
        if (!r.isOk) throw BiliException("finger/spi 失败：HTTP ${r.code} ${r.preview}")

        val resp = decode<ApiResp<FingerData>>(r.body, "finger/spi")
        val data = resp.data ?: throw BiliException("finger/spi 无 data：${r.preview}")

        CookieStore.put("buvid3", data.b3)
        CookieStore.put("buvid4", data.b4)
        log("  buvid3 长度 ${data.b3.length}，buvid4 长度 ${data.b4.length}")
        if (data.b3.isEmpty()) throw BiliException("buvid3 为空，后续请求大概率 412")
        return data
    }

    // ------------------------------------------------------------------- 2. nav

    /**
     * nav 有两个用途：拿 WBI 的 img_key / sub_key，以及判断登录态。
     * 未登录时 code = -101 但 data 仍然存在，所以这里不能按 code 判失败。
     */
    fun fetchNav(log: (String) -> Unit): NavData {
        val r = BiliHttp.get("$BASE/x/web-interface/nav")
        log("  HTTP ${r.code}")
        if (!r.isOk) throw BiliException("nav 失败：HTTP ${r.code} ${r.preview}")

        val resp = decode<ApiResp<NavData>>(r.body, "nav")
        val data = resp.data ?: throw BiliException("nav 无 data（code=${resp.code}）：${r.preview}")

        data.wbiImg?.let {
            Wbi.updateFromUrls(it.imgUrl, it.subUrl)
            log("  WBI key 已就绪：${Wbi.ready()}")
        }
        log("  登录态：${data.isLogin}${if (data.isLogin) "（${data.uname}）" else "（未登录，能出声但拿不到高音质）"}")
        return data
    }

    // ------------------------------------------------------------------ 3. view

    fun fetchView(bvid: String, log: (String) -> Unit): ViewData {
        val r = BiliHttp.get("$BASE/x/web-interface/view?bvid=$bvid")
        log("  HTTP ${r.code}")
        if (!r.isOk) throw BiliException("view 失败：HTTP ${r.code} ${r.preview}")

        val resp = decode<ApiResp<ViewData>>(r.body, "view")
        val data = resp.data
        if (resp.code != 0 || data == null) {
            throw BiliException("view code=${resp.code} ${resp.message}：${r.preview}")
        }
        log("  aid=${data.aid} cid=${data.cid} 标题=《${data.title.take(24)}》")
        return data
    }

    // --------------------------------------------------------------- 4. playurl

    /**
     * 先走 wbi 签名版，取不到 DASH 再退回非签名版。
     * 两条路径都打日志，因为「能不能拿到 dash.audio」是整个方案成立的前提。
     */
    fun fetchPlayUrl(bvid: String, cid: Long, log: (String) -> Unit): PlayUrlData {
        val params = linkedMapOf(
            "bvid" to bvid,
            "cid" to cid.toString(),
            "fnval" to "4048",
            "fnver" to "0",
            "fourk" to "1",
            "qn" to "127",
            "platform" to "pc",
        )

        if (Wbi.ready()) {
            val query = Wbi.sign(params)
            val r = BiliHttp.get("$BASE/x/player/wbi/playurl?$query")
            log("  wbi/playurl → HTTP ${r.code}")
            tryParse(r.body)?.let { return it }
            log("  签名版未返回 DASH，回退非签名接口")
        } else {
            log("  WBI key 缺失，直接走非签名接口")
        }

        val plain = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        val r2 = BiliHttp.get("$BASE/x/player/playurl?$plain")
        log("  playurl → HTTP ${r2.code}")
        return tryParse(r2.body)
            ?: throw BiliException("playurl 未返回 DASH：HTTP ${r2.code} ${r2.preview}")
    }

    private fun tryParse(body: String): PlayUrlData? {
        val resp = try {
            json.decodeFromString<ApiResp<PlayUrlData>>(body)
        } catch (e: Exception) {
            return null
        }
        return resp.data?.takeIf { it.dash != null }
    }

    // -------------------------------------------------------------- 5. 选音轨

    /**
     * 优先级：FLAC → 普通音频里码率最高的。
     * 故意跳过 Dolby：小米电视4A 这颗 Amlogic T962 没有 EC-3 授权解码器，
     * 选它只会换来一段沉默，而且失败得很安静，很难查。
     */
    fun pickAudio(data: PlayUrlData, log: (String) -> Unit): AudioPick {
        val dash = data.dash ?: throw BiliException("响应里没有 dash")

        dash.audio?.forEach {
            log("  普通音轨 id=${it.id} ${it.bandwidth / 1000}kbps ${it.codecs} ${it.mimeType}")
        }
        dash.flac?.audio?.let {
            log("  FLAC 音轨 ${it.bandwidth / 1000}kbps ${it.codecs}")
        }
        dash.dolby?.audio?.forEach {
            log("  Dolby 音轨 ${it.bandwidth / 1000}kbps（本机无 EC-3 解码，跳过）")
        }

        val flac = dash.flac?.audio
        if (flac != null && flac.baseUrl.isNotEmpty()) {
            return AudioPick(
                kind = "FLAC",
                url = flac.baseUrl,
                backupUrls = flac.backupUrl,
                detail = "${flac.bandwidth / 1000}kbps · ${flac.codecs} · 备份 ${flac.backupUrl.size} 个",
            )
        }

        val best: DashData = dash.audio.orEmpty()
            .filter { it.baseUrl.isNotEmpty() && it.mimeType != "audio/eac3" }
            .maxByOrNull { it.bandwidth }
            ?: throw BiliException("dash.audio 为空，或全部条目无 base_url")

        return AudioPick(
            kind = "普通音频",
            url = best.baseUrl,
            backupUrls = best.backupUrl,
            detail = "${best.bandwidth / 1000}kbps · ${best.codecs} · 备份 ${best.backupUrl.size} 个",
        )
    }
}
