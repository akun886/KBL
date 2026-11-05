package com.kunplayer.tv.cache

import android.content.Context
import com.kunplayer.tv.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 本地收藏。
 *
 * 刻意不放在 B 站账号的收藏夹里：那条路要扫码登录，而登录是为了同步，
 * 不是为了「能不能收藏」——一个音乐播放器如果连收藏都要先注册，是没有道理的。
 * 本地存的 Track 已含 bvid + cid，播放时不需要再打 view 接口，收藏列表断网也能显示。
 *
 * 内存里留一份，避免每次按 MENU 都读盘；写入仍走「临时文件 + rename」，
 * 防断电把 JSON 写成半截。
 */
object Favorites {

    private const val FILE = "favorites.json"
    private const val TMP = "favorites.tmp"
    private const val MAX = 200

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    @Serializable
    private data class Entry(val tracks: List<Track> = emptyList())

    private var cache: MutableList<Track>? = null

    /** 最近的收藏排在前面——用户刚收藏的东西，十有八九马上要听 */
    fun load(context: Context): List<Track> {
        cache?.let { return it.toList() }
        val list = read(context)
        cache = list
        return list.toList()
    }

    fun contains(context: Context, bvid: String): Boolean =
        load(context).any { it.bvid == bvid }

    /** 返回 true 表示这次操作是「加入收藏」 */
    fun toggle(context: Context, track: Track): Boolean {
        if (!track.playable) return false
        val list = cache ?: read(context).also { cache = it }
        val at = list.indexOfFirst { it.bvid == track.bvid }
        val added = if (at >= 0) {
            list.removeAt(at)
            false
        } else {
            list.add(0, track)
            if (list.size > MAX) list.removeAt(list.lastIndex)
            true
        }
        write(context, list)
        return added
    }

    /**
     * 把收藏状态设成指定值，而不是翻转。
     *
     * 云端是收藏状态的权威来源，本地这份只是它的镜像。两边已经不一致时
     * （比如换了个账号、或者云端收藏被别人删了），翻转会把状态越搞越乱，
     * 所以这里只认「设成收藏」或「设成不收藏」。
     *
     * @return true 表示状态确实变了
     */
    fun set(context: Context, track: Track, favorited: Boolean): Boolean {
        if (!track.playable) return false
        val list = cache ?: read(context).also { cache = it }
        val at = list.indexOfFirst { it.bvid == track.bvid }
        if (favorited && at < 0) {
            list.add(0, track)
            if (list.size > MAX) list.removeAt(list.lastIndex)
            write(context, list)
            return true
        }
        if (!favorited && at >= 0) {
            list.removeAt(at)
            write(context, list)
            return true
        }
        return false
    }

    private fun read(context: Context): MutableList<Track> {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return ArrayList()
        return try {
            json.decodeFromString<Entry>(f.readText()).tracks.toMutableList()
        } catch (t: Throwable) {
            f.delete()
            ArrayList()
        }
    }

    private fun write(context: Context, list: List<Track>) {
        val tmp = File(context.filesDir, TMP)
        val target = File(context.filesDir, FILE)
        try {
            tmp.writeText(json.encodeToString(Entry.serializer(), Entry(list)))
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        } catch (t: Throwable) {
            tmp.delete()
        }
    }
}
