package com.kunplayer.tv.net

import com.kunplayer.tv.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 多 P 稿件 = 一张合集。
 *
 * 音乐区里「一张专辑」最常见的形态就是一个 100 P 的稿件：稿件标题是专辑名，
 * 每个 P 是一首歌。接口把它们压成一条记录下发，只带 P1 的 cid——
 * 照单全收的话，这张专辑在应用里就是一本书只有第一页，按下一首还得跳到别人家去。
 *
 * 所以这里补一次 `view`：`pages[]` 一次给全所有 P（cid / 歌名 / 时长），
 * 100 P 的响应大约 8KB，比逐首打 playurl 便宜得多。
 *
 * 缓存分两层，都是按 bvid：
 *   · 内存 LRU 8 张 —— 切歌、来回上一首时不必再问
 *   · 磁盘 40 张、24 小时过期 —— 冷启动后重听昨天的专辑不联网
 *
 * 不做「列表页批量预取」：首页 30 条就是 30 次 view，A53 上要把首屏拖慢一秒多，
 * 而且这是最像爬虫的一种请求模式。列表角标拿不到 P 数就先不标，播过一次自然就有了。
 */
object CollectionApi {

    /**
     * 一张合集。
     *
     * `parts` 直接就是可播放的 Track——它们和单曲走同一条取流、心跳、歌词链路，
     * 不另造一个「分 P」类型，否则每个下游都要写两份。
     */
    @Serializable
    data class Album(
        val bvid: String = "",
        val title: String = "",
        val cover: String = "",
        val owner: String = "",
        val parts: List<Track> = emptyList(),
        /** 落盘时间，只用来判过期 */
        val at: Long = 0L,
    ) {
        val isCollection: Boolean get() = parts.size > 1
    }

    /** 一天。合集是会长的（UP 主接着传），缓存不能当永久事实 */
    private const val TTL_MS = 24 * 60 * 60 * 1000L
    private const val MEM_MAX = 8
    private const val DISK_MAX = 40

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private var dir: File? = null

    private val mem = object : LinkedHashMap<String, Album>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Album>?): Boolean =
            size > MEM_MAX
    }

    /** 正在拉的 bvid。用 java.lang.Object 是为了 wait/notify，Kotlin 的 Any 没有这两个方法 */
    private val lock = java.lang.Object()
    private val loading = HashSet<String>()

    fun init(filesDir: File) {
        dir = File(filesDir, "albums").apply { mkdirs() }
    }

    /** 已经在手上就立即返回，供界面在拉取过程中先画一版 */
    fun cached(bvid: String): Album? {
        if (bvid.isEmpty()) return null
        synchronized(mem) { mem[bvid] }?.let { if (fresh(it)) return it }
        val disk = readDisk(bvid) ?: return null
        if (!fresh(disk)) return null
        synchronized(mem) { mem[bvid] = disk }
        return disk
    }

    /**
     * 只看内存，不读盘。
     *
     * 首页一屏 30 行，每行都去 stat + 解析一个文件，A53 上会在滚列表时抖；
     * 而这里要的只是「这个 bvid 我们是不是已经知道它是合集了」。
     */
    fun peek(bvid: String): Album? {
        if (bvid.isEmpty()) return null
        return synchronized(mem) { mem[bvid] }
    }

    /**
     * 内存没有就翻磁盘。冷启动后内存是空的，但磁盘上还有 24 小时内的合集表——
     * 那正是最需要角标的时候（用户昨天听过一张专辑，今天打开应用还想认出来）。
     *
     * 读盘要开文件，首页一屏 30 行就是 30 次 stat，所以只在内存没命中时才试，
     * 而且读失败就当没有，不能让角标把首屏拖住。
     */
    fun peekOrDisk(bvid: String): Album? {
        peek(bvid)?.let { return it }
        val disk = readDisk(bvid) ?: return null
        if (!fresh(disk)) return null
        synchronized(mem) { mem[bvid] = disk }
        return disk
    }

    /**
     * 拿一张合集。**必须在 IO 线程调用**。
     *
     * 单 P 稿件也会返回（parts 只有一条），调用方据此知道「这不是合集」，
     * 省得下次再问一遍。
     *
     * 同一个 bvid 只打一次接口：播放器和播放页几乎同时发现「这首属于合集」，
     * 各拉一遍的话，一张专辑的首播会发三次一模一样的 view。
     * 后到的那一路在这里等前一路的结果。
     */
    fun load(bvid: String, log: (String) -> Unit): Album? {
        if (bvid.isEmpty()) return null
        cached(bvid)?.let { return it }

        synchronized(lock) {
            val deadline = System.currentTimeMillis() + 4000
            while (bvid in loading && System.currentTimeMillis() < deadline) {
                runCatching { lock.wait(200) }
            }
            cached(bvid)?.let { return it }
            loading.add(bvid)
        }
        return try {
            fetch(bvid, log)
        } finally {
            synchronized(lock) {
                loading.remove(bvid)
                lock.notifyAll()
            }
        }
    }

    private fun fetch(bvid: String, log: (String) -> Unit): Album? {
        val data = runCatching { BiliApi.fetchView(bvid, log) }.getOrNull() ?: return null
        val cover = FeedApi.coverUrl(data.pic, 480, 270)
        val owner = data.owner?.name.orEmpty()
        // 合集才把稿件名写进每一条：单曲的 album 留空，界面上就不会多出一行
        // 和歌名一模一样的「合集名」
        val albumTitle = if (data.pages.size > 1) data.title else ""

        val parts = data.pages.mapNotNull { p ->
            val cid = p.cid
            if (cid <= 0) return@mapNotNull null
            Track(
                bvid = bvid,
                aid = data.aid,
                cid = cid,
                title = p.part.ifEmpty { data.title }.trim(),
                cover = cover,
                owner = owner,
                durationSec = p.duration,
                album = albumTitle,
                pages = data.pages.size,
                pageNo = p.page,
            )
        }
        if (parts.isEmpty()) {
            log("合集解析为空：$bvid")
            return null
        }

        val album = Album(
            bvid = bvid,
            title = data.title,
            cover = cover,
            owner = owner,
            parts = parts,
            at = System.currentTimeMillis(),
        )
        synchronized(mem) { mem[bvid] = album }
        writeDisk(album)
        if (album.isCollection) log("合集《${data.title.take(20)}》${parts.size} 首")
        return album
    }

    private fun fresh(album: Album): Boolean =
        album.at > 0 && System.currentTimeMillis() - album.at < TTL_MS

    // ------------------------------------------------------------ 磁盘

    private fun fileOf(bvid: String): File? = dir?.let { File(it, "$bvid.json") }

    private fun readDisk(bvid: String): Album? {
        val f = fileOf(bvid) ?: return null
        if (!f.exists()) return null
        return runCatching { json.decodeFromString<Album>(f.readText()) }.getOrNull()
    }

    private fun writeDisk(album: Album) {
        val f = fileOf(album.bvid) ?: return
        runCatching {
            f.writeText(json.encodeToString(Album.serializer(), album))
            trim()
        }
    }

    private fun trim() {
        val files = dir?.listFiles() ?: return
        if (files.size <= DISK_MAX) return
        files.sortedBy { it.lastModified() }
            .take(files.size - DISK_MAX)
            .forEach { it.delete() }
    }
}
