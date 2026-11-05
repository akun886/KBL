package com.kunplayer.tv.cache

import com.kunplayer.tv.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors

/**
 * 每首歌听到哪儿了。
 *
 * 键是 `bvid#cid` 而不是 bvid：一张 100 P 的合集共用一个 bvid，只按 bvid 存，
 * 听到第 40 首时会把第 1 首的进度覆盖掉。搜索来源的 cid 是 0，那时候只剩 bvid 可用，
 * 也就只能精确到「这条稿件的第一 P」——这是数据本身能给到的极限。
 *
 * 落盘用「临时文件 + renameTo」，和首屏缓存同一套：电视拔电源是常态，
 * 直接写目标文件会留下一个截断的 JSON，下次启动整份进度都读不出来。
 *
 * 只在 IO 线程写文件；主线程只改内存里的表，所以挂在一个单线程 executor 上。
 */
object Resume {

    private const val FILE = "resume.json"
    private const val MAX = 600

    /** 少于这么久不算「听过」，不记也不续——刚点开就切歌不该留下痕迹 */
    private const val MIN_KEEP_MS = 5_000L

    /** 离结尾不到这么久就不续了：从那几个字开始放，等于放完就跳下一首 */
    private const val TAIL_MS = 8_000L

    @Serializable
    private data class Entry(val pos: Long = 0, val dur: Long = 0, val at: Long = 0)

    @Serializable
    private data class Doc(val items: Map<String, Entry> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kun-resume").apply { isDaemon = true }
    }

    private val map = HashMap<String, Entry>()
    private var dir: File? = null
    private var loaded = false
    private var dirty = false
    private var lastFlush = 0L

    fun init(filesDir: File) {
        dir = filesDir
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val f = dir?.let { File(it, FILE) } ?: return
        if (!f.exists()) return
        runCatching {
            val doc = json.decodeFromString<Doc>(f.readText())
            map.clear()
            map.putAll(doc.items)
        }
    }

    fun key(t: Track): String = "${t.bvid}#${t.cid}"

    /** 上次听到哪儿。没有、太短、太靠后都返回 0（= 从头放） */
    fun position(t: Track): Long {
        ensureLoaded()
        val e = map[key(t)] ?: return 0
        if (e.pos < MIN_KEEP_MS) return 0
        if (e.dur > 0 && e.pos > e.dur - TAIL_MS) return 0
        return e.pos
    }

    /**
     * 记一次进度。节流写在 [flush] 里，这里只改内存——ticker 每 500ms 就会调一次，
     * 每次都落盘等于拿闪存当内存用。
     */
    fun track(t: Track, pos: Long, dur: Long) {
        ensureLoaded()
        if (t.bvid.isEmpty() || pos < MIN_KEEP_MS) return
        map[key(t)] = Entry(pos, dur, System.currentTimeMillis())
        dirty = true
    }

    fun clear(t: Track) {
        ensureLoaded()
        if (map.remove(key(t)) != null) dirty = true
    }

    /** 切歌 / 暂停 / 退出时调。距上次写盘不到 3 秒就并到下一次 */
    fun flush(force: Boolean = true) {
        ensureLoaded()
        if (!dirty) return
        val now = System.currentTimeMillis()
        if (!force && now - lastFlush < 3_000L) return
        dirty = false
        lastFlush = now
        val snapshot = HashMap(map)
        io.execute { write(snapshot) }
    }

    /** 播放到一半也要定期落一次：进程被系统杀掉时不会走 onPause */
    fun tickFlush() = flush(force = false)

    private fun write(snapshot: HashMap<String, Entry>) {
        val d = dir ?: return
        val f = File(d, FILE)
        val trimmed = if (snapshot.size <= MAX) {
            snapshot
        } else {
            // 超上限按时间淘汰最旧的。听过的歌会越来越多，不裁剪最后是一个几 MB 的文件
            val sorted = snapshot.entries.sortedByDescending { it.value.at }
            HashMap<String, Entry>().apply {
                for (e in sorted.take(MAX)) put(e.key, e.value)
            }
        }
        val tmp = File(d, "$FILE.tmp")
        runCatching {
            tmp.writeText(json.encodeToString(Doc(trimmed)))
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        }
    }
}
