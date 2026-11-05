package com.kunplayer.tv.net

import kotlinx.serialization.json.Json

/**
 * Phase 4 各接口共用的解码器。
 *
 * 与 BiliApi 里那个是同一套宽松参数：B 站这些接口对同一路径会返回结构不一致的 JSON
 * （空收藏夹没有 medias 键、未登录 nav 的 code 是 -101 但 data 完好），
 * 严格模式只会把能用的响应判死。
 */
internal val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
}

internal inline fun <reified T> String.decodeApi(what: String): T =
    try {
        ApiJson.decodeFromString<T>(this)
    } catch (e: Exception) {
        throw BiliException("$what 解析失败（${e.javaClass.simpleName}）。原文前 160 字：${take(160)}")
    }

/** 接口里常见的 `//i0.hdslb.com/...` 与 `http://` 两种写法，统一提成 https */
fun String.httpsUrl(): String = when {
    isEmpty() -> ""
    startsWith("//") -> "https:$this"
    startsWith("http://") -> "https://" + removePrefix("http://")
    else -> this
}
