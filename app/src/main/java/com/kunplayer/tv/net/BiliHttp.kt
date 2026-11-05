package com.kunplayer.tv.net

import okhttp3.Cache
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 全局唯一的 OkHttpClient：接口请求和 ExoPlayer 取流共用同一个实例。
 *
 * 关键点：Referer / Origin / UA 必须逐请求挂上。B 站 CDN 校验 Referer，
 * WebView 里靠 Electron 拦截器做的事，在这里就是三个 header。
 * 这也正是上一版「网页壳」方案在 Android 上必然 403 的原因。
 */
object BiliHttp {

    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    const val REFERER = "https://www.bilibili.com/"

    private const val ORIGIN = "https://www.bilibili.com"

    private var diskCache: Cache? = null

    /**
     * 由 Application 调用。80MB 磁盘缓存主要是为封面服务：
     * 第二次开电视时封面直接出图，不必等 15 张图重新下载。
     */
    fun init(cacheDir: File) {
        if (diskCache == null) {
            diskCache = Cache(File(cacheDir, "http"), 80L * 1024 * 1024)
        }
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cache(diskCache)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun newRequest(url: String, withCookie: Boolean = true): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", REFERER)
            .header("Origin", ORIGIN)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")

        if (withCookie) {
            val cookie = CookieStore.header()
            if (cookie.isNotEmpty()) builder.header("Cookie", cookie)
        }
        return builder.build()
    }

    /**
     * 同步 GET。调用方负责切到 IO 线程。
     * 返回 HTTP 状态码 + 响应体原文，风控排查时需要看这两样。
     */
    fun get(url: String, withCookie: Boolean = true): HttpResult {
        return try {
            client.newCall(newRequest(url, withCookie)).execute().use { resp: Response ->
                // Set-Cookie 必须一起带出来：扫码登录的登录态实际是走响应头下发的，
                // data.url 只有部分场景才带全参数。只认 url 会在真机上「扫完没反应」。
                HttpResult(resp.code, resp.body?.string().orEmpty(), resp.headers("Set-Cookie"))
            }
        } catch (t: Throwable) {
            // 老设备上 TLS 握手失败会走到这里（Android 6 的信任库偏旧）
            HttpResult(-1, "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * 表单 POST。心跳（历史记录）是唯一必须 POST 的接口，且必须带 csrf=bili_jct，
     * 缺了它服务端返回 -111「csrf 校验失败」——GET 拼同样的参数不会报错，但也不会记账。
     */
    fun postForm(url: String, form: Map<String, String>): HttpResult {
        val body = FormBody.Builder()
            .apply { form.forEach { (k, v) -> add(k, v) } }
            .build()
        val request = newRequest(url).newBuilder()
            .post(body)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build()
        return try {
            client.newCall(request).execute().use { resp: Response ->
                HttpResult(resp.code, resp.body?.string().orEmpty())
            }
        } catch (t: Throwable) {
            HttpResult(-1, "${t.javaClass.simpleName}: ${t.message}")
        }
    }
}

data class HttpResult(
    val code: Int,
    val body: String,
    val setCookies: List<String> = emptyList(),
) {
    val isOk: Boolean get() = code == 200
    /** 日志里只用前 220 字符，避免整屏刷满 */
    val preview: String get() = if (body.length > 220) body.take(220) + "…" else body
}
