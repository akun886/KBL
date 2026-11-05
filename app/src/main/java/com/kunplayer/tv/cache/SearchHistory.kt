package com.kunplayer.tv.cache

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 搜索历史。最近搜过的词，最新的排在前面，最多留 [MAX] 条。
 *
 * 存的是**词**，不是结果。结果几分钟就旧了（合集下架、UP 改名），
 * 词永远是对的——按一下再搜一次，拿到的总是当下的一批。
 *
 * 为什么不放 B 站的历史搜索接口：那条路要登录态且会污染手机 App 的搜索记录，
 * 而这个键盘本来就是本地的（只有 a-z、不碰输入法），历史也跟着留在本地最自洽。
 *
 * 内存里留一份，避免每开一次搜索页都读盘；写盘走「临时文件 + rename」，
 * 防断电把 JSON 写成半截——和 Favorites 同一种写法。
 */
object SearchHistory {

    private const val FILE = "search_history.json"
    private const val TMP = "search_history.tmp"

    /** 留十条：再多就得滚动，「再搜一次」的价值也跟着被稀释 */
    private const val MAX = 10

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    @Serializable
    private data class Entry(val words: List<String> = emptyList())

    private var cache: MutableList<String>? = null

    fun load(context: Context): List<String> {
        cache?.let { return it.toList() }
        val list = read(context)
        cache = list
        return list.toList()
    }

    /**
     * 记一次搜索。
     *
     * 已经有的词要**挪到最前**而不是留原位：用户反复搜同一个词，
     * 说明这正是他现在要的，它应该在第一个。
     */
    fun add(context: Context, word: String) {
        val w = word.trim()
        if (w.isEmpty()) return
        val list = cache ?: read(context).also { cache = it }
        list.removeAll { it.equals(w, ignoreCase = true) }
        list.add(0, w)
        while (list.size > MAX) list.removeAt(list.lastIndex)
        write(context, list)
    }

    fun remove(context: Context, word: String) {
        val list = cache ?: read(context).also { cache = it }
        if (list.removeAll { it == word }) write(context, list)
    }

    fun clear(context: Context) {
        val list = cache ?: read(context).also { cache = it }
        if (list.isEmpty()) return
        list.clear()
        write(context, list)
    }

    private fun read(context: Context): MutableList<String> {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return ArrayList()
        return try {
            json.decodeFromString<Entry>(f.readText()).words.toMutableList()
        } catch (t: Throwable) {
            f.delete()
            ArrayList()
        }
    }

    private fun write(context: Context, list: List<String>) {
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
