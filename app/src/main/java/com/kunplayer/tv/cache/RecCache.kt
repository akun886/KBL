package com.kunplayer.tv.cache

import android.content.Context
import com.kunplayer.tv.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors

/**
 * 「推荐音乐」上一次换出来的那一批。
 *
 * 冷启动直接端上屏，不重新搜：这一页每次重搜要两三秒，而开机第一眼要的是
 * 「昨天那批还在」，不是先空两秒再整屏换掉。只有用户主动按「换一批」、
 * 或在侧栏上再确认一次推荐音乐，才重新抽关键词，并把新的一批写回这里。
 *
 * 写盘用「临时文件 + renameTo」：电视拔电源是常态，直接写目标文件会留下
 * 一个截断的 JSON，下次启动连旧的一批都读不出来。读只在冷启动做一次，
 * 文件就是 30 条 Track，几 KB。
 */
object RecCache {

    private const val FILE = "rec_music.json"
    private const val TMP = "rec_music.json.tmp"

    @Serializable
    private data class Doc(val tracks: List<Track> = emptyList(), val at: Long = 0L)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kun-rec").apply { isDaemon = true }
    }

    /** 上一次刷新结果。文件坏了就当没有，绝不让一份坏 JSON 把首屏拖住 */
    fun load(c: Context): List<Track> {
        val f = File(c.filesDir, FILE)
        if (!f.exists()) return emptyList()
        return try {
            json.decodeFromString(Doc.serializer(), f.readText()).tracks
        } catch (t: Throwable) {
            f.delete()
            emptyList()
        }
    }

    /** 只在真的换到新的一批时写。空结果不覆盖上一批，刷新失败也不写 */
    fun save(c: Context, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val copy = tracks.toList()
        io.execute {
            val tmp = File(c.filesDir, TMP)
            val target = File(c.filesDir, FILE)
            try {
                tmp.writeText(json.encodeToString(Doc.serializer(), Doc(copy, System.currentTimeMillis())))
                if (!tmp.renameTo(target)) {
                    target.delete()
                    tmp.renameTo(target)
                }
            } catch (t: Throwable) {
                tmp.delete()
            }
        }
    }
}
