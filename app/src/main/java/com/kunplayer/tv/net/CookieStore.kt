package com.kunplayer.tv.net

import android.content.Context
import android.content.SharedPreferences

/**
 * 极简 Cookie 存储：只保留 key=value，不做域匹配和过期。
 *
 * 为什么不做 CookieJar：bv 的接口层是把 sessData / mid 当显式参数传给 playurl 的，
 * 媒体请求只认 Referer + UA。所以这里只需要能手工拼一行 Cookie 头。
 *
 * 必须落地到 SharedPreferences：buvid3 每次冷启动都重新申请的话，
 * 设备指纹一直在变，反而是风控最容易盯上的行为。
 */
object CookieStore {

    private const val PREF = "cookies"

    private val store = linkedMapOf<String, String>()
    private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs = p
        p.all.forEach { (key, value) ->
            if (value is String && value.isNotEmpty()) store[key] = value
        }
    }

    @Synchronized
    fun put(name: String, value: String) {
        if (value.isEmpty()) return
        store[name] = value
        prefs?.edit()?.putString(name, value)?.apply()
    }

    @Synchronized
    fun get(name: String): String? = store[name]

    @Synchronized
    fun has(name: String): Boolean = store.containsKey(name)

    @Synchronized
    fun header(): String = store.entries.joinToString("; ") { "${it.key}=${it.value}" }

    @Synchronized
    fun dump(): String =
        if (store.isEmpty()) "（空）"
        else store.entries.joinToString("\n") { "  ${it.key} = ${it.value.take(28)}" }

    @Synchronized
    fun clear() {
        store.clear()
        prefs?.edit()?.clear()?.apply()
    }

    /** 只删一项。退出登录要保留 buvid3/buvid4，那是设备指纹，删了等于换一台新设备 */
    @Synchronized
    fun remove(name: String) {
        store.remove(name)
        prefs?.edit()?.remove(name)?.apply()
    }
}
