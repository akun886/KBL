package com.kunplayer.tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kunplayer.tv.audio.AudioBus
import com.kunplayer.tv.cache.Resume
import com.kunplayer.tv.cache.Settings
import com.kunplayer.tv.model.AudioPick
import com.kunplayer.tv.model.Track
import com.kunplayer.tv.net.BiliApi
import com.kunplayer.tv.net.CollectionApi
import com.kunplayer.tv.net.Heartbeat
import com.kunplayer.tv.net.Wbi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/** 队列推进方式。label 直接上屏，所以写成中文而不是枚举名 */
enum class QueueMode(val label: String) {
    ORDER("顺序"),
    ONE("单曲"),
    SHUFFLE("随机");

    fun roll(): QueueMode = values()[(ordinal + 1) % values().size]
}

/**
 * 队列播放：按 OK 播放整行，从按下的那张卡开始，播完自动下一首。
 *
 * 这一版把它提升成进程内单例，原因只有一个：播放要活得比界面久。
 * 回桌面、电视休眠、Activity 被回收时音乐不能停——控制器只有挂在
 * PlaybackService 上（而不是 Activity 上）才做得到。界面通过
 * addListener 订阅状态，销毁时 removeListener，不再持有播放器本身。
 *
 * 取流（playurl）在 IO 线程做，且只在切歌时做一次。A53 上签名一次要几十毫秒，
 * 每次切歌都重签会听出停顿；而流地址大约两小时过期，所以 30 分钟内复用同一份结果。
 *
 * 切歌会取消上一次还没回来的取流任务：连按三下「下一首」时，
 * 如果三次取流都排着队跑完再依次起播，听到的会是三首歌的接缝。
 */
class PlaybackController private constructor(private val context: Context) {

    interface Listener {
        fun onTrack(track: Track, index: Int, total: Int) {}
        fun onTick(positionMs: Long, durationMs: Long, playing: Boolean) {}
        fun onMode(mode: QueueMode) {}
        fun onError(message: String) {}
    }

    companion object {
        private const val TAG = "KunPlayer"

        /** 连续这么多首取不到流就停下来，否则一个坏源会让它把整行空跑一遍 */
        private const val MAX_FAILURE = 3

        /**
         * 取到的流地址有效期约两小时。单曲循环、来回上一首时不必每次都重签——
         * 30 分钟是个保守值，既省掉绝大多数重复请求，又不会把过期地址端给播放器。
         */
        private const val RESOLVE_TTL_MS = 30 * 60 * 1000L

        private const val PREF = "player"

        /** 队列落盘的键与上限。200 首足够覆盖「上次听的那个歌单」，再多就是浪费 IO */
        private const val KEY_QUEUE = "queue"
        private const val KEY_INDEX = "queue_index"
        private const val SAVE_MAX = 200

        @Volatile
        private var instance: PlaybackController? = null

        fun get(context: Context): PlaybackController =
            instance ?: synchronized(this) {
                instance ?: PlaybackController(context.applicationContext).also { instance = it }
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    private var player: AudioPlayer? = null
    private var queue: List<Track> = emptyList()
    private var index = -1
    private var loadJob: Job? = null

    /** 正在查「这首是不是某张合集的一页」。切歌时取消，免得把上一首的合集装进来 */
    private var expandJob: Job? = null
    private var failure = 0
    private var generation = 0

    /** 当前曲目的全部 CDN 地址，第一个失败就试下一个 */
    private var urls: List<String> = emptyList()
    private var urlIndex = 0

    private var cache: Resolved? = null

    var mode: QueueMode = QueueMode.ORDER
        private set

    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            val pos = p.positionMs
            val dur = p.durationMs
            val playing = p.isPlaying
            // 频谱与列表里的小动画都读这个标记：它必须跟着真实出声状态走，
            // 而不是跟着「有没有队列」走
            AudioBus.playing = playing
            queue.getOrNull(index)?.let { Resume.track(it, pos, dur) }
            Resume.tickFlush()
            for (l in listeners) l.onTick(pos, dur, playing)
            handler.postDelayed(this, 500)
        }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    init {
        val pref = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        mode = runCatching {
            QueueMode.valueOf(pref.getString("mode", QueueMode.ORDER.name)!!)
        }.getOrDefault(QueueMode.ORDER)
        restoreQueue()
    }

    /**
     * 队列落盘。
     *
     * 不存这一步的话，每次冷启动首页底栏都是空的——而「打开就看见上次听到的那首歌」
     * 是听歌软件最基本的记忆。只存歌单不自动播放：电视开机不该突然出声。
     */
    private fun saveQueue() {
        runCatching {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY_QUEUE, json.encodeToString(queue.take(SAVE_MAX)))
                .putInt(KEY_INDEX, index)
                .apply()
        }
    }

    private fun restoreQueue() {
        runCatching {
            val pref = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val raw = pref.getString(KEY_QUEUE, null) ?: return
            val list = json.decodeFromString<List<Track>>(raw)
            if (list.isEmpty()) return
            queue = list
            index = pref.getInt(KEY_INDEX, 0).coerceIn(0, list.lastIndex)
        }
    }

    // ------------------------------------------------------------ 订阅

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    // ------------------------------------------------------------ 队列

    fun playQueue(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty()) return
        // 起播时才拉起前台服务：没在放歌就不该挂一条常驻通知
        PlaybackService.start(context)
        // 换队列之前先把上一首听到哪儿记下来，否则「正在收听」换一页就是一整条进度丢失
        saveCurrent(flush = true)
        // 必须拷贝。调用方传进来的常常是列表适配器内部那个 ArrayList，
        // 而适配器换一页就会 clear() + addAll() 原地改写——队列跟着一起被换掉，
        // 于是「正在收听」永远等于「最后浏览的那一页」，切目的地不播歌也把队列换了。
        queue = tracks.toList()
        index = startIndex.coerceIn(0, tracks.lastIndex)
        failure = 0
        saveQueue()
        load(queue[index])
        // 这一条可能只是某张合集的第 1 页。先让它出声，再悄悄把整张装进队列——
        // 等 view 回来才起播的话，A53 上要多等一次往返才开始有声音。
        expandCollection(queue[index])
    }

    /**
     * 把「一首歌」换成「它所在的那张合集」。
     *
     * 只换队列，不重新取流：用户已经在听这一首了，重新 load 会把播放头拨回 0，
     * 听感上是一句没唱完又从头开始。
     */
    private fun expandCollection(track: Track) {
        expandJob?.cancel()
        val gen = generation
        expandJob = scope.launch {
            val album = withContext(Dispatchers.IO) {
                runCatching { CollectionApi.load(track.bvid) { log(it) } }.getOrNull()
            } ?: return@launch
            if (gen != generation) return@launch          // 用户已经切到别的歌
            if (!album.isCollection) return@launch
            // 定位用实际拿到的 cid，不看 track.cid：
            // 收藏夹与搜索来的稿件 cid 是 0（列表接口不给），
            // 而这些来源里恰恰藏着最多合集——用 track.cid 匹配永远找不到位置。
            // 取流时补出来的 cid 就是顶层 cid，也就是第一页的 cid。
            val at = when {
                track.cid > 0 -> album.parts.indexOfFirst { it.cid == track.cid }
                else -> 0
            }
            if (at < 0) return@launch                     // 手里这个 cid 不在分 P 表里，不动

            // 队列已经是这张合集（从曲目列表点进来的）：只把下标校准一下
            val same = queue.size == album.parts.size &&
                queue.firstOrNull()?.bvid == album.bvid
            if (same) {
                if (index != at) {
                    index = at
                    saveQueue()
                }
                return@launch
            }
            if (queue.getOrNull(index)?.bvid != track.bvid) return@launch

            queue = album.parts
            index = at
            saveQueue()
            log("合集展开 ${album.parts.size} 首，当前第 ${at + 1} 首")
            // 重画计数与人声信息：标题要换成 P 名，总数要从 1 变成 100
            for (l in listeners) l.onTrack(queue[index], index, queue.size)
        }
    }

    /** 直接跳到队列里的第几个。曲目列表用它，不必先拼一份新列表 */
    fun playAt(position: Int) {
        if (position !in queue.indices) return
        if (position == index) {
            ticker.run()
            return
        }
        saveCurrent(flush = true)
        index = position
        failure = 0
        saveQueue()
        load(queue[index])
    }

    fun next() = step(1)

    fun prev() = step(-1)

    fun toggle() {
        val p = player
        if (p == null) {
            // 冷启动后队列是从磁盘恢复的，播放器还没建。这一下就是「接着听」
            if (hasTrack()) {
                PlaybackService.start(context)
                load(queue[index])
                // 恢复出来的那一条可能也是一张合集的一页：这时候才补齐整张，
                // 否则「接着听」会把这张专辑卡在同一首上，按下一首还是它
                expandCollection(queue[index])
            }
            return
        }
        if (p.isPlaying) {
            p.pause()
            // 暂停时停表：不停的话「听了 3 分钟、暂停 20 分钟」会被记成 23 分钟，
            // 历史里的进度条会比实际长一大截
            pauseHeartbeat()
            // 暂停是「我要走了」的最强信号，进度立刻落盘
            saveCurrent(flush = true)
        } else {
            p.resume()
            resumeHeartbeat()
        }
        AudioBus.playing = p.isPlaying
        ticker.run()
    }

    fun seekTo(ms: Long) {
        player?.seekTo(ms.coerceAtLeast(0))
        ticker.run()
    }

    fun isPlaying(): Boolean = player?.isPlaying == true

    fun hasTrack(): Boolean = queue.getOrNull(index) != null

    fun currentTrack(): Track? = queue.getOrNull(index)

    /**
     * 当前队列的只读快照。首页的「正在收听」要显示完整一列，
     * 只给「第几首 / 共几首」是不够的——那样列表里没得选。
     */
    fun queueSnapshot(): List<Track> = queue.toList()

    fun cycleMode() {
        mode = mode.roll()
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString("mode", mode.name).apply()
        for (l in listeners) l.onMode(mode)
        log("队列模式 → ${mode.label}")
    }

    /** 界面刚打开时拿一次全量状态，不必等下一次 tick */
    class Snapshot(
        val track: Track?,
        val index: Int,
        val total: Int,
        val positionMs: Long,
        val durationMs: Long,
        val playing: Boolean,
        val mode: QueueMode,
    )

    fun snapshot(): Snapshot {
        val p = player
        return Snapshot(
            track = queue.getOrNull(index),
            index = index,
            total = queue.size,
            positionMs = p?.positionMs ?: 0L,
            durationMs = p?.durationMs ?: 0L,
            playing = p?.isPlaying == true,
            mode = mode,
        )
    }

    /**
     * 遥控器上没有播放/暂停键，所以「按 OK 打当前正在播的那首」就是暂停/继续。
     * 返回 false 表示这不是当前曲目，调用方应正常起播。
     *
     * 比的是 cid 不是 bvid：一张合集里 100 首共用同一个 bvid，
     * 按 bvid 比会把「合集里的另一首」认成「正在播的这首」，按确认就变成了暂停。
     */
    fun toggleIfCurrent(cid: Long): Boolean {
        if (queue.getOrNull(index)?.cid != cid || player == null) return false
        toggle()
        return true
    }

    private fun step(delta: Int) {
        if (queue.isEmpty()) return
        saveCurrent(flush = true)
        index = pickIndex(delta)
        failure = 0
        saveQueue()
        load(queue[index])
    }

    /**
     * 把「现在听到哪儿」记下来。
     *
     * 队列每次被换掉、每次切歌、每次暂停都要先走这一下：ticker 每 500ms 才记一次，
     * 用户在两拍之间按了下一首，最后那几秒就丢了。
     */
    private fun saveCurrent(flush: Boolean) {
        val t = queue.getOrNull(index) ?: return
        val p = player ?: return
        val pos = p.positionMs
        if (pos >= 5_000) Resume.track(t, pos, p.durationMs)
        if (flush) Resume.flush()
    }

    private fun pickIndex(delta: Int): Int {
        val size = queue.size
        if (size <= 1) return index
        if (mode == QueueMode.SHUFFLE) {
            if (size == 2) return 1 - index
            var n: Int
            // 随机不该等于「原地重播」，也不该和上一首一模一样
            do n = Random.nextInt(size) while (n == index)
            return n
        }
        return ((index + delta) % size + size) % size
    }

    private fun load(track: Track, startAtMs: Long = 0L) {
        loadJob?.cancel()
        beatJob?.cancel()
        beatTrack = null
        generation++
        val gen = generation

        // 断点续播：没指定起点就查这首歌上次听到哪儿。太短、太靠后都由 Resume 判掉，
        // 这里拿到的一定是一个「值得从这儿接着放」的位置
        val from = if (startAtMs > 0) startAtMs else Resume.position(track)

        for (l in listeners) l.onTrack(track, index, queue.size)

        loadJob = scope.launch {
            val resolved = withContext(Dispatchers.IO) { cachedOrResolve(track) }

            if (resolved == null) {
                if (gen != generation) return@launch
                failure++
                if (failure >= MAX_FAILURE) {
                    failure = 0
                    notifyError("连续 $MAX_FAILURE 首取不到流，先停在这里")
                    return@launch
                }
                // 单曲取流失败（下架、地区限制）不该让整个队列停住
                step(1)
                return@launch
            }
            if (gen != generation) return@launch

            failure = 0
            startPlayback(track, resolved, from, gen)
        }
    }

    private fun startPlayback(track: Track, resolved: Resolved, startAtMs: Long, gen: Int) {
        urls = (listOf(resolved.pick.url) + resolved.pick.backupUrls)
            .filter { it.isNotEmpty() }
            .distinct()
        urlIndex = 0

        val p = player ?: AudioPlayer(context) { log(it) }.also { player = it }
        // 回调在这里绑而不是构造时绑：换歌要换掉闭包里的 generation，
        // 否则上一首的「播放结束」会把刚起播的新歌推走
        p.onEnded = { if (gen == generation) onAutoEnded() }
        p.onError = { if (gen == generation) onStreamError(track) }

        p.play(urls[0], startAtMs)
        handler.removeCallbacks(ticker)
        handler.post(ticker)

        // 只有真的开播了才开始记账，取流失败的那首不该进历史
        startHeartbeat(track, resolved.cid)
    }

    /** 播完一首（不是用户按的下一首） */
    private fun onAutoEnded() {
        if (mode == QueueMode.ONE) {
            // 单曲循环不该再走一次取流：把播放头拨回 0 直接重播
            player?.seekTo(0)
            player?.resume()
            beatPlayed = 0
            beatStart = System.currentTimeMillis() / 1000
            return
        }
        step(1)
    }

    /**
     * 流地址失效（CDN 403 / 404 / 断流）。B 站每首歌都给 2~4 个备份域名，
     * 换一个通常就好了；全都失败才算这一首取不到，往下推。
     */
    private fun onStreamError(track: Track) {
        if (urlIndex + 1 < urls.size) {
            urlIndex++
            log("  主 CDN 失败，切备用 $urlIndex/${urls.size - 1}")
            player?.play(urls[urlIndex], 0)
            return
        }
        // 地址可能只是过期了：清掉缓存，让下一首重新取
        cache = null
        failure++
        if (failure >= MAX_FAILURE) {
            failure = 0
            notifyError("连续 $MAX_FAILURE 首取不到流，先停在这里")
            return
        }
        log("  《${track.title.take(12)}》全部 CDN 失败，跳下一首")
        step(1)
    }

    // ------------------------------------------------------------ 心跳

    /**
     * 心跳：登录后每 15 秒记一次账，历史页才有内容。
     *
     * 必须跑在 IO 线程：这是条 POST，放在 Handler 的 Runnable 里会直接
     * NetworkOnMainThreadException——第一版就是这么写的，日志里一行红字，历史永远不涨。
     * 用 Job 而不是 Handler：切歌时 cancel 一下就能停，不必记 callback。
     */
    private var beatJob: Job? = null
    private var beatTrack: Track? = null
    private var beatCid = 0L
    private var beatStart = 0L
    private var beatPlayed = 0

    private fun startHeartbeat(track: Track, cid: Long) {
        beatJob?.cancel()
        beatTrack = track
        beatCid = cid
        beatPlayed = 0
        beatStart = System.currentTimeMillis() / 1000
        resumeHeartbeat()
    }

    private fun resumeHeartbeat() {
        beatJob?.cancel()
        val track = beatTrack ?: return
        val cid = beatCid
        if (cid <= 0) return
        beatJob = scope.launch {
            while (isActive) {
                val played = beatPlayed
                withContext(Dispatchers.IO) {
                    Heartbeat.beat(track, cid, played, beatStart) { log(it) }
                }
                beatPlayed += Heartbeat.INTERVAL_SEC
                delay(Heartbeat.INTERVAL_SEC * 1000L)
            }
        }
    }

    private fun pauseHeartbeat() {
        beatJob?.cancel()
        beatJob = null
    }

    // ------------------------------------------------------------ 取流

    private class Resolved(val pick: AudioPick, val cid: Long) {
        val at = System.currentTimeMillis()
    }

    private fun cachedOrResolve(track: Track): Resolved? {
        // 缓存键必须是 cid 而不是 bvid：一张 100 P 的合集共用一个 bvid，
        // 按 bvid 命中的话，切到第 2 首会继续拿到第 1 首的流地址——
        // 表现是「整张专辑只会放第一首」，而日志里每一首都是 200。
        val hit = cache
        if (hit != null && hit.cid == track.cid &&
            System.currentTimeMillis() - hit.at < RESOLVE_TTL_MS
        ) {
            return hit
        }
        val fresh = resolve(track) ?: return null
        cache = fresh
        return fresh
    }

    private fun resolve(track: Track): Resolved? {
        return try {
            if (!Wbi.ready()) runCatching { BiliApi.fetchNav { log(it) } }
            val cid = if (track.cid > 0) track.cid else BiliApi.fetchView(track.bvid) { log(it) }.cid
            val data = BiliApi.fetchPlayUrl(track.bvid, cid) { log(it) }
            // 音质每一首都重读一次偏好：设置里改完不必重启，下一首就是新档。
            // 已经解好的地址仍按 RESOLVE_TTL_MS 复用，不会因为切歌反复重签
            Resolved(BiliApi.pickAudio(data, Settings.quality(context)) { log(it) }, cid)
        } catch (c: CancellationException) {
            // 取流被取消（用户已经按了下一首），不能吞掉，否则 cancel 失效
            throw c
        } catch (t: Throwable) {
            log("取流异常 ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /**
     * 音质档位改过：把已经解好的流地址作废。
     *
     * 只丢缓存、不动正在播的这首——切档不该把歌打断。下一首（或再点这一首）
     * 就会按新档重新走一次 playurl，日志里有「选轨 档位=…」那一条可以对账。
     */
    fun invalidateStream() {
        cache = null
        log("音质档位已切换，下一次取流生效")
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        saveCurrent(flush = true)
        AudioBus.playing = false
        loadJob?.cancel()
        beatJob?.cancel()
        scope.cancel()
        player?.release()
        player = null
    }

    private fun notifyError(message: String) {
        for (l in listeners) l.onError(message)
    }

    private fun log(line: String) {
        Log.i(TAG, line)
    }
}
