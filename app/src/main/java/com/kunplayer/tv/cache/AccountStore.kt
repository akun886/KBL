package com.kunplayer.tv.cache

import android.content.Context
import android.content.SharedPreferences
import com.kunplayer.tv.net.LoginApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 一个登录过的账号。只存「这个人是谁」和「他那套鉴权 Cookie」。
 *
 * 头像和身份一起存下来，是为了账号列表在断网时也能画出来：
 * 切账号这个动作本来就是为了「换个人听」，先转两秒圈再告诉你在选谁很别扭。
 */
@Serializable
data class SavedAccount(
    val mid: Long = 0L,
    val uname: String = "",
    val face: String = "",
    /** 那一行身份，与「我的」页写的是同一个串（「年度大会员」/「大会员已过期（…）」） */
    val vip: String = "",
    /** 只含鉴权 Cookie，不含 buvid */
    val cookies: Map<String, String> = emptyMap(),
    /** 最近一次使用的时间。列表按它倒序，常用的那个自然沉到最上面 */
    val savedAt: Long = 0L,
)

/**
 * 登录过的账号档案。
 *
 * 「切换账号」不是「退出再登」：退掉的那个如果只剩一次重扫，用户来回切一次就得掏一次手机。
 * 这里按 mid 存下每个账号的鉴权 Cookie，切过去时整套写回 [com.kunplayer.tv.net.CookieStore]。
 *
 * **buvid3/buvid4 不进档案**：那是设备指纹，跟着这台电视走，不跟着账号走。
 * 每个账号存一份，等于让同一台机器在服务端看来是好几台设备——而这正是风控最敏感的信号。
 */
object AccountStore {

    private const val PREF = "accounts"
    private const val KEY = "list"

    /** 上限六个。再多就不是「切换」而是「账号管理」了，电视上没人做这件事 */
    private const val MAX = 6

    private val json = Json { ignoreUnknownKeys = true }
    private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }

    /** 按最近使用倒序。文件坏了就当时没有档案——这儿抛异常会让「我的」页整个打不开 */
    @Synchronized
    fun all(): List<SavedAccount> {
        val raw = prefs?.getString(KEY, "").orEmpty()
        if (raw.isEmpty()) return emptyList()
        return runCatching { json.decodeFromString<List<SavedAccount>>(raw) }
            .getOrDefault(emptyList())
            .filter { it.mid > 0 && it.cookies.isNotEmpty() }
            .sortedByDescending { it.savedAt }
    }

    /**
     * 把「现在这套 Cookie」记成 mid 这个账号的档案。每次拿到 nav 都可以调，按 mid 覆盖。
     *
     * 拿不到 SESSDATA 就不记：那说明根本没登录，记下来只会在列表里多一行点不动的空账号。
     */
    @Synchronized
    fun remember(mid: Long, uname: String, face: String, vip: String) {
        if (mid <= 0) return
        val cookies = LoginApi.snapshot()
        if (cookies["SESSDATA"].isNullOrEmpty()) return
        val kept = all().filterNot { it.mid == mid }
        val me = SavedAccount(mid, uname, face, vip, cookies, System.currentTimeMillis())
        save((listOf(me) + kept).take(MAX))
    }

    /** 切换时也要把 savedAt 刷新一遍，这样「刚用过的」排在第一个 */
    @Synchronized
    fun touch(acc: SavedAccount) {
        val kept = all().filterNot { it.mid == acc.mid }
        save((listOf(acc.copy(savedAt = System.currentTimeMillis())) + kept).take(MAX))
    }

    @Synchronized
    fun forget(mid: Long) {
        if (mid <= 0) return
        save(all().filterNot { it.mid == mid })
    }

    private fun save(list: List<SavedAccount>) {
        prefs?.edit()?.putString(KEY, json.encodeToString<List<SavedAccount>>(list))?.apply()
    }
}
