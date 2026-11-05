package com.kunplayer.tv.ui

import android.app.Activity
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kunplayer.tv.R
import com.kunplayer.tv.diag.Diag
import com.kunplayer.tv.model.Track
import com.kunplayer.tv.net.CollectionApi
import com.kunplayer.tv.net.FavState
import com.kunplayer.tv.net.FeedApi
import com.kunplayer.tv.net.LoginApi
import com.kunplayer.tv.net.LyricsApi
import com.kunplayer.tv.player.PlaybackController
import com.kunplayer.tv.player.QueueMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放页：正在播放的那一首。
 *
 * 这一页只负责画。动作全部挂在图标上：光标移到图标上按确认才执行。
 *
 * 之前是「方向键 = 动作」：左右切歌、确认暂停，全页只有封面与进度条两个焦点位。
 * 遥控器上「左右」首先是导航，让它同时又是切歌，用户按错一次就换了一首，
 * 停下来都不知道自己在第几首。现在方向键只管移动焦点，动作一律点到图标才做。
 *
 * 背景是当前封面放大后的模糊，按 bvid 缓存；换歌不重算同一张。
 */
class NowPlaying(
    private val activity: Activity,
    private val playback: PlaybackController,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val hint: (String) -> Unit,
    private val onToggle: () -> Unit,
    private val onCycleMode: () -> Unit,
    /** 点歌手名：打开作者主页，到那儿关注 */
    private val onOpenArtist: (Track) -> Unit,
) {

    private companion object {
        /** 一次按键跳 5 秒。10 秒太粗，按三下就跨过一整段；2 秒要按十几次才舍得跳 */
        const val SEEK_STEP_MS = 5_000L

        /**
         * 跳转后多久内以「目标位置」为准显示。
         * 播放器 seek 完的 position 有一小段是旧的，那期间照实画会让进度条往回跳一下，
         * 连按几次就是一连串抖动。这个窗口只是遮住那一下，过后仍然回到真实进度。
         */
        const val SEEK_HOLD_MS = 700L

        /** 按键说明在屏幕上停留多久 */
        const val KEYS_HINT_MS = 6_000L
    }

    private val bg: ImageView = activity.findViewById(R.id.player_bg)
    private val coverBox: View = activity.findViewById(R.id.player_cover_box)
    private val coverWrap: View = activity.findViewById(R.id.player_cover_wrap)
    private val emptyBlock: View = activity.findViewById(R.id.player_empty)
    private val coverRing: View = activity.findViewById(R.id.player_cover_ring)
    private val lyricsView: LyricsView = activity.findViewById(R.id.player_lyrics)
    private val lyricsEmpty: TextView = activity.findViewById(R.id.player_lyrics_empty)
    private val spectrum: SpectrumView = activity.findViewById(R.id.player_spectrum)
    private val cover: ImageView = activity.findViewById(R.id.player_cover)
    private val title: TextView = activity.findViewById(R.id.player_title)
    private val artist: TextView = activity.findViewById(R.id.player_artist)
    private val albumView: TextView = activity.findViewById(R.id.player_album)
    private val listHead: View = activity.findViewById(R.id.player_list_head)
    private val listCount: TextView = activity.findViewById(R.id.player_list_count)
    private val listClose: View = activity.findViewById(R.id.player_list_close)
    private val listView: RecyclerView = activity.findViewById(R.id.player_list)
    private val seek: View = activity.findViewById(R.id.player_seek)
    private val fill: View = activity.findViewById(R.id.player_fill)
    private val thumb: View = activity.findViewById(R.id.player_thumb)
    private val posView: TextView = activity.findViewById(R.id.player_pos)
    private val durView: TextView = activity.findViewById(R.id.player_dur)
    private val nextHint: TextView = activity.findViewById(R.id.player_next_hint)
    private val btnMode: ImageView = activity.findViewById(R.id.player_mode)
    private val btnList: ImageView = activity.findViewById(R.id.player_list_open)
    private val btnPrev: ImageView = activity.findViewById(R.id.player_prev)
    private val btnPlay: ImageView = activity.findViewById(R.id.player_play)
    private val btnNext: ImageView = activity.findViewById(R.id.player_next)
    private val btnFav: ImageView = activity.findViewById(R.id.player_fav)
    private val keys: TextView = activity.findViewById(R.id.player_keys)
    private val bar: View = activity.findViewById(R.id.player_bar)

    private var track: Track? = null
    private var playing = false
    private var duration = 0L

    /** 当前这首的歌词。空 = 这首歌没有可用字幕，右半边留白 */
    private var lyrics: List<LyricsApi.Line> = emptyList()
    private var lyricJob: Job? = null

    /** 已经拉过歌词的 cid。同一首切回来不再打接口 */
    private var lyricCid = 0L

    /** 这一首的歌词问完了没有。没问完时右栏不写字，免得「加载中」和「没有」看起来一样 */
    private var lyricLoaded = false

    /**
     * 空态写什么。0 表示沿用 XML 里的「暂无歌词」。
     *
     * 「B 站没给字幕」和「字幕不像歌词」是两件事，都写「暂无歌词」，
     * 用户只会以为应用坏了，然后再问一次。
     */
    private var lyricNote = 0

    /**
     * 右栏的三档：歌词、正在听的那一串、频谱。同一块位置同时只站一样。
     *
     * 循环顺序照着用户念的顺序：歌词 → 列表 → 频谱 → 歌词。没有歌词时
     * 「歌词」这一档本来就没东西可看，循环里跳过，变成频谱 ⇄ 列表两档，
     * 于是「无歌词默认频谱」和「循环切换」不打架。
     */
    private enum class Pane { LYRICS, LIST, SPECTRUM }

    /** 冷启动与换歌都先给频谱：歌词要问接口，这一栏不能空着等 */
    private var pane = Pane.SPECTRUM

    /** 用户有没有自己翻过档。没翻过时歌词一到就按「有词给词、没词给频谱」自动定位 */
    private var userPicked = false

    /** 从哪一档翻进列表的。收起列表回到这一档，不是一律回歌词 */
    private var beforeList = Pane.SPECTRUM

    private fun defaultPane(): Pane = if (lyrics.isNotEmpty()) Pane.LYRICS else Pane.SPECTRUM

    /** 下一档。没词时不给「歌词」这一档，免得按一下翻进一屏空白 */
    private fun nextPane(from: Pane): Pane = when (from) {
        Pane.LYRICS -> Pane.LIST
        Pane.LIST -> Pane.SPECTRUM
        Pane.SPECTRUM -> if (lyrics.isNotEmpty()) Pane.LYRICS else Pane.LIST
    }

    /**
     * 当前这首所在的合集。单曲时是 null。
     *
     * 它决定了「播放列表」里放什么：合集放这一张的曲目，其余放播放队列。
     * 两者本来就是同一件事——从合集里起播时，队列就是那些曲目。
     */
    private var album: CollectionApi.Album? = null

    /** 手上这份曲目表属于哪个 bvid。合集内切歌不必重拉 */
    private var albumBvid = ""
    private var albumJob: Job? = null

    private val listAdapter = PartAdapter { position, _ -> playFromList(position) }
    private val listLayout = LinearLayoutManager(activity)

    /** 跳转目标与它的有效期，见 SEEK_HOLD_MS */
    private var previewMs = 0L
    private var previewUntil = 0L

    init {
        fill.pivotX = 0f

        coverBox.setOnClickListener { onToggle() }
        btnPlay.setOnClickListener { onToggle() }
        btnPrev.setOnClickListener { playback.prev() }
        btnNext.setOnClickListener { playback.next() }
        // 进度条上按确认 = 播放/暂停。一个能聚焦的控件按确认却没反应，会被当成失灵
        seek.setOnClickListener { onToggle() }
        // 模式与收藏也是图标：点到才生效
        btnMode.setOnClickListener { onCycleMode() }
        btnFav.setOnClickListener { toggleFavorite() }

        // 播放列表：进右栏，和歌词轮值
        listView.layoutManager = listLayout
        listView.adapter = listAdapter
        listView.itemAnimator = null
        // 右下角这一颗是右栏的循环档位键：图标画的是「按下去会到哪一档」，
        // 不写标签也不加第二颗按钮——播放页不为一件事再常驻一块区域
        btnList.setOnClickListener { cyclePane() }
        listClose.setOnClickListener { closeList() }
        paintPaneButton()

        // 歌手名：这张卡上唯一通向「这个人」的入口
        artist.setOnClickListener { track?.let(onOpenArtist) }
        artist.setOnFocusChangeListener { _, hasFocus ->
            paintArtist(hasFocus)
            if (hasFocus) Diag.enter("播放页 歌手")
        }

        seek.setOnFocusChangeListener { _, hasFocus ->
            Diag.enter(if (hasFocus) "进度条" else "封面")
            // 拖动点常显了，聚焦就靠放大一圈：换颜色没有意义（它本来就是白的），
            // 换尺寸才是「这条现在归你操作」的常用说法
            thumb.animate().cancel()
            thumb.animate().scaleX(if (hasFocus) 1.5f else 1f)
                .scaleY(if (hasFocus) 1.5f else 1f)
                .setDuration(120)
                .start()
        }

        // 播放键本来就是白底圆，聚焦时靠放大而不是换底色表示
        btnPlay.setOnFocusChangeListener { v, hasFocus ->
            v.animate().cancel()
            v.animate().scaleX(if (hasFocus) 1.12f else 1f)
                .scaleY(if (hasFocus) 1.12f else 1f)
                .setDuration(160)
                .start()
        }

        // 收藏：44dp 的白心浮在页面底色上，焦点只能靠底色 + 放大来说。
        // 不换颜色——未收藏是描边白心、已收藏是实心红心，颜色已经在说「收藏了没有」，
        // 再让它兼职表示焦点，两件事就打起来了
        btnFav.setOnFocusChangeListener { v, hasFocus ->
            v.animate().cancel()
            v.animate().scaleX(if (hasFocus) 1.08f else 1f)
                .scaleY(if (hasFocus) 1.08f else 1f)
                .setDuration(140)
                .start()
            if (hasFocus) Diag.enter("播放页 收藏")
        }

        // 封面聚焦：边提亮只对深色封面有效（浅色封面上白边抬不过图的白），
        // 所以真正的反馈是卡外那道环——它浮在页面底色上，跟封面颜色无关。
        // 卡上的常驻亮边不再跟着变：环已经说了「选中」，再提亮就是框里套框
        coverBox.setOnFocusChangeListener { v, hasFocus ->
            v.animate().cancel()
            v.animate().scaleX(if (hasFocus) 1.02f else 1f)
                .scaleY(if (hasFocus) 1.02f else 1f)
                .setDuration(180)
                .start()
            coverRing.animate().cancel()
            coverRing.animate().alpha(if (hasFocus) 1f else 0f).setDuration(140).start()
        }
    }

    val isSeekFocused: Boolean get() = seek.hasFocus()

    /** 按键说明显示几秒后淡出：它是「第一次来」的说明书，不是常驻装饰 */
    fun fadeKeysHint() {
        keys.animate().cancel()
        keys.alpha = 1f
        keys.postDelayed({
            keys.animate()
                .alpha(0f)
                .setDuration(600)
                .withEndAction { keys.visibility = View.INVISIBLE }
                .start()
        }, KEYS_HINT_MS)
    }

    /** 从播放器快照恢复整屏状态。界面重建（回桌面再回来）走这里 */
    fun restore() {
        val snap = playback.snapshot()
        val current = snap.track
        if (current == null) {
            showEmpty()
            return
        }
        paintTrack(current, snap.index)
        paintMode(snap.mode)
        loadLyrics(current)        // 冷启动恢复出来的队列没有播放器，playing 是 false。不先把内存值取反，
        // paintProgress 里那句「状态没变就不重画」会跳过，按钮停在 XML 默认的暂停图标上
        playing = !snap.playing
        paintProgress(snap.positionMs, snap.durationMs, snap.playing)
    }

    fun showEmpty() {
        track = null
        lyricJob?.cancel()
        lyricCid = 0L
        lyrics = emptyList()
        lyricLoaded = false
        lyricNote = 0
        lyricsView.clear()
        lyricsView.visibility = View.GONE
        lyricsEmpty.visibility = View.GONE
        spectrum.visibility = View.GONE
        // 空态没有「这一串」可言：列表、抬头、合集名一起收起来
        albumJob?.cancel()
        album = null
        albumBvid = ""
        pane = Pane.SPECTRUM
        userPicked = false
        paintPaneButton()
        listAdapter.submit(emptyList())
        listView.visibility = View.GONE
        listHead.visibility = View.GONE
        albumView.visibility = View.GONE
        // 歌名与歌手现在长在封面卡上，跟着卡一起收起；空态用 player_empty 顶替卡的位置
        title.text = ""
        artist.text = ""
        nextHint.visibility = View.INVISIBLE
        posView.text = ""
        durView.text = ""
        fill.scaleX = 0f
        // 没在放歌的时候，进度条和三个控制键按下去都不会有反应。
        // 留着它们等于让遥控器对着空气按，所以整行收起
        seek.visibility = View.INVISIBLE
        bar.visibility = View.INVISIBLE
        btnFav.visibility = View.INVISIBLE
        // 不画封面卡：一个空的圆角方块读起来是「加载失败」，不是「还没有歌」。
        // 空态换成同尺寸的一段字，位置和卡片完全重合，起播时不会跳一下
        coverWrap.visibility = View.GONE
        emptyBlock.visibility = View.VISIBLE
        cover.setImageDrawable(null)
        bg.setImageDrawable(null)
        btnPlay.setImageResource(R.drawable.ic_play)
    }

    // --------------------------------------------------------- 播放器回调

    fun onTrackChanged(track: Track, index: Int, total: Int) {
        // 换歌必须清掉跳转预览：否则上一首的「目标位置」会被画到新歌的进度条上
        previewUntil = 0L
        previewMs = 0L
        // 换歌回到自动档：这一首有词就给词，没词就停在频谱，
        // 不把上一首手动翻到的那一档带过来。
        // 例外是「人正在列表里选歌」：列表必须留着，paintTrack → syncCollection
        // 里的 refreshList() 会把新的一行重绑、焦点钉回正在播那首。
        // 这时候收掉列表，焦点会落进一块 GONE 的视图，之后每个方向键都像按在空处
        if (pane != Pane.LIST) {
            userPicked = false
            pane = Pane.SPECTRUM
        }
        paintTrack(track, index)
        loadLyrics(track)
    }

    fun onProgress(positionMs: Long, durationMs: Long, isPlaying: Boolean) {
        paintProgress(positionMs, durationMs, isPlaying)
    }

    fun onModeChanged(mode: QueueMode) {
        paintMode(mode)
        // 模式换了，下一首是不是可预告也跟着换
        paintNextHint(playback.snapshot().index)
    }

    // --------------------------------------------------------- 拖动

    fun nudge(deltaMs: Long) {
        if (duration <= 0) return
        val base = if (SystemClock.elapsedRealtime() < previewUntil) {
            previewMs
        } else {
            playback.snapshot().positionMs
        }
        previewMs = (base + deltaMs).coerceIn(0L, duration)
        previewUntil = SystemClock.elapsedRealtime() + SEEK_HOLD_MS
        playback.seekTo(previewMs)
        paintProgress(previewMs, duration, playing)
        Diag.confirm(if (deltaMs > 0) "快进 5 秒" else "快退 5 秒")
    }

    // --------------------------------------------------------- 绘制

    private fun paintTrack(track: Track, index: Int) {
        this.track = track
        coverWrap.visibility = View.VISIBLE
        emptyBlock.visibility = View.GONE
        coverBox.visibility = View.VISIBLE
        seek.visibility = View.VISIBLE
        bar.visibility = View.VISIBLE
        btnFav.visibility = View.VISIBLE
        title.text = track.title
        artist.text = track.owner
        paintArtist(artist.hasFocus())
        paintNextHint(index)

        // 合集名单独一行。刚起播时 track.album 还是空的（合集要等 view 回来才知道），
        // 那一瞬间只显示歌名，解析完成后再补上——比先显示「加载中」再换掉要安静
        albumView.text = track.album
        albumView.visibility = if (track.album.isEmpty()) View.GONE else View.VISIBLE

        syncCollection(track)

        // 封面 315×177dp，2 倍密度 630×354px。按 B 站原图的 16:9 取图，
        // 不裁成 1:1——裁掉的是两侧各四分之一的画面
        CoverLoader.load(cover, FeedApi.reCrop(track.cover, 630, 354), 630, 354)
        paintFavoriteState()
        paintBackground(track)
    }

    /**
     * 收藏状态以云端收藏夹为准。
     *
     * 索引是懒的：第一次进播放页时还没有，所以先按本地镜像画一次，
     * 索引到了再重画一次。两次都不算错，只是第二次才是权威答案。
     */
    private fun paintFavoriteState() {
        val t = track ?: return
        paintFavorite(FavState.isFavorited(activity, t.bvid))
        if (!LoginApi.isLoggedIn()) return
        scope.launch {
            withContext(Dispatchers.IO) { FavState.ensureIndexed(activity, log) }
            if (track?.bvid != t.bvid) return@launch
            paintFavorite(FavState.isFavorited(activity, t.bvid))
        }
    }

    private fun paintBackground(track: Track) {
        val want = track.bvid
        // 背景铺满全屏，用 16:9 原比例取图，模糊之后看不出接缝
        CoverLoader.fetch(FeedApi.reCrop(track.cover, 640, 360), 640, 360) { bmp ->
            if (bmp == null || this.track?.bvid != want) return@fetch
            bg.setImageBitmap(Blur.of(want, bmp))
        }
    }

    private fun paintProgress(positionMs: Long, durationMs: Long, isPlaying: Boolean) {
        duration = durationMs
        // 刚跳完的 700ms 内以目标位置为准，避免进度条被旧的 position 拽回去
        val pos = if (SystemClock.elapsedRealtime() < previewUntil) previewMs else positionMs
        val fraction = if (durationMs > 0) (pos.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

        fill.scaleX = fraction
        posView.text = fmt(pos)
        durView.text = if (durationMs > 0) fmt(durationMs) else "--:--"
        placeThumb(fraction)

        if (isPlaying != playing) {
            playing = isPlaying
            btnPlay.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        }

        // 歌词用 pos 而不是 positionMs：seek 之后的那 700ms 里进度条画的是目标位置，
        // 歌词要是照真实位置走，两句就对不上了
        if (lyricsView.visibility == View.VISIBLE) lyricsView.seekTo(pos)
    }

    /** 拖动点要落在线的末端；宽度没量出来之前先不画，等下一次 tick——那里一定已经布局完成 */
    private fun placeThumb(fraction: Float) {
        val width = seek.width - seek.paddingStart - seek.paddingEnd
        val size = thumb.width
        if (width <= 0 || size <= 0) return
        thumb.translationX = (width - size) * fraction
    }

    /**
     * 「下一首」预告。
     *
     * 只在顺序模式画：单曲循环的下一首就是它自己，写出来等于说了一句废话；
     * 随机的下一首在按下去之前根本不存在，预告不了。没有的东西不画。
     */
    private fun paintNextHint(index: Int) {
        val queue = playback.queueSnapshot()
        val next = if (playback.snapshot().mode == QueueMode.ORDER
            && queue.size > 1
            && index in queue.indices
        ) {
            queue[(index + 1) % queue.size]
        } else {
            null
        }
        if (next == null) {
            // INVISIBLE 而不是 GONE：这一行占着 16dp 的固定高，控制行才不会
            // 在「有下一首 / 没有下一首」之间上下跳一次
            nextHint.visibility = View.INVISIBLE
        } else {
            nextHint.text = activity.getString(R.string.now_up_next, next.title)
            nextHint.visibility = View.VISIBLE
        }
    }

    private fun paintMode(mode: QueueMode) {
        val (icon, name) = when (mode) {
            QueueMode.ORDER -> R.drawable.ic_mode_order to R.string.mode_order
            QueueMode.ONE -> R.drawable.ic_mode_one to R.string.mode_one
            QueueMode.SHUFFLE -> R.drawable.ic_mode_shuffle to R.string.mode_shuffle
        }
        btnMode.setImageResource(icon)
        // 文字没了，名称还得有个地方存着：焦点落到它身上时读屏要能报出来
        btnMode.contentDescription = activity.getString(name)
    }

    /**
     * 歌手名的两种状态：平时是页面底色上的一行灰字，聚焦时提亮并加下划线。
     *
     * 这一版歌名与歌手都搬到了封面下方，不再压在照片上，所以颜色从
     * text_on_cover（为照片上调过的亮灰）换成 text_secondary——
     * 纯底上不需要再抬一档，抬了反而跟歌名抢。
     *
     * 下划线是这一页唯一一处「看起来能按」的文字提示。换成底板会在
     * 歌手与合集名之间再插一块方块，把这一行拆成两段。
     */
    private fun paintArtist(on: Boolean) {
        artist.setTextColor(
            activity.resources.getColor(
                if (on) R.color.text_primary else R.color.text_secondary,
                activity.theme,
            )
        )
        val flags = artist.paintFlags
        artist.paintFlags = if (on) {
            flags or Paint.UNDERLINE_TEXT_FLAG
        } else {
            flags and Paint.UNDERLINE_TEXT_FLAG.inv()
        }
    }

    private fun paintFavorite(on: Boolean) {
        btnFav.setImageResource(if (on) R.drawable.ic_heart_on else R.drawable.ic_heart)
        // 不 tint：ic_heart 自己就是白描边，ic_heart_on 自己是红的。
        // 原来取 bar_icon（聚焦时转成深色），那套是给「白底圆填充」用的；
        // 现在心浮在封面上、底下垫着半透黑圆，聚焦再转深色就等于把图标擦掉了
        btnFav.imageTintList = null
    }

    fun toggleFavorite() {
        val current = track ?: run {
            hint(activity.getString(R.string.now_nothing))
            return
        }
        scope.launch {
            // 云端收藏要走网络，不能在主线程上做
            val outcome = withContext(Dispatchers.IO) {
                runCatching { FavState.toggle(activity, current, log) }
                    .onFailure { log("收藏失败 ${it.javaClass.simpleName}: ${it.message}") }
            }
            outcome.onSuccess { added ->
                paintFavorite(added)
                hint(activity.getString(if (added) R.string.fav_added else R.string.fav_removed))
                Diag.confirm(if (added) "收藏" else "取消收藏")
            }.onFailure {
                hint(activity.getString(R.string.fav_failed))
            }
        }
    }

    /**
     * 打开播放页时把光标放在底部的播放键上。
     *
     * 必须 post：播放页是刚从 GONE 翻成 VISIBLE 的，这一刻还没测量布局，
     * 直接 `requestFocus()` 会失败——焦点被系统塞给第一个抢得到的控件，
     * 实测落在进度条上。用户打开播放页第一眼要能按下去的就是「播 / 停」，
     * 不是一条只能左右拖的滑杆。
     */
    fun focusPlay() {
        // 回到播放页就是「听歌」：右栏回到自动档（有词给词、没词给频谱），
        // 列表是临时翻过去看的，不该留在屏上
        if (pane == Pane.LIST) {
            userPicked = false
            pane = defaultPane()
            paintRight()
        }
        btnPlay.post { btnPlay.requestFocus() }
    }

    // --------------------------------------------------------- 歌词

    /**
     * 拉这一首的歌词。网络与磁盘都在 IO 线程。
     *
     * 回调回来先比对 cid：连按两次下一首时，前一首的字幕可能后到，
     * 晚到的结果不许上屏。
     */
    private fun loadLyrics(track: Track) {
        // 只有「问到过同一首」才值得跳过。收藏夹和搜索结果给的 cid 是 0，
        // 而 lyricCid 初值也是 0——直接比会判成同一首，一进来就返回，
        // 右栏连「暂无歌词」都不会写，是整块空白。cid 交给 LyricsApi 自己按 bvid 反查。
        if (track.cid > 0 && track.cid == lyricCid) return
        lyricCid = track.cid
        lyricJob?.cancel()
        lyrics = emptyList()
        lyricLoaded = false
        lyricNote = 0
        lyricsView.clear()
        paintRight()
        // 匿名拿不到字幕（接口返回 code=0，列表为空），省一次必然是空的请求，
        // 但要说清是「没登录」而不是「这首歌没歌词」——前者登录一下就有了
        if (!LoginApi.isLoggedIn()) {
            lyricNote = R.string.now_lyrics_need_login
            lyricLoaded = true
            if (!userPicked) pane = defaultPane()
            paintRight()
            return
        }

        lyricJob = scope.launch {
            val got = withContext(Dispatchers.IO) {
                runCatching { LyricsApi.load(track, log) }
                    .onFailure { log("歌词失败 ${it.javaClass.simpleName}: ${it.message}") }
                    .getOrNull()
            }
            if (lyricCid != track.cid) return@launch
            when (got) {
                is LyricsApi.Outcome.Ok -> {
                    lyrics = got.lines
                    lyricNote = 0
                }
                is LyricsApi.Outcome.No -> {
                    lyrics = emptyList()
                    lyricNote = noteOf(got.why)
                }
                null -> {
                    lyrics = emptyList()
                    lyricNote = R.string.now_lyrics_net
                }
            }
            lyricLoaded = true
            lyricsView.submit(lyrics)
            // 没手动翻过档就按这一首的实际情况定位：有词给词，没词留在频谱
            if (!userPicked) pane = defaultPane()
            paintRight()
        }
    }

    /** 没拿到歌词时右栏该写的那句话 */
    private fun noteOf(why: LyricsApi.Why): Int = when (why) {
        LyricsApi.Why.NO_SUBTITLE -> R.string.now_lyrics_no_sub
        LyricsApi.Why.NOT_LYRIC -> R.string.now_lyrics_not_lyric
        LyricsApi.Why.NETWORK -> R.string.now_lyrics_net
        LyricsApi.Why.NO_CID -> R.string.now_lyrics_none
    }

    /**
     * 右栏只画一档：歌词、频谱，或者正在听的那一串。
     *
     * 三档共用同一块位置，是因为它们都在回答「这首歌现在是什么样、后面还有哪些」。
     * 并排放会各占半屏，两边都读不全。
     */
    private fun paintRight() {
        val showLyrics = pane == Pane.LYRICS && lyrics.isNotEmpty()
        // 没词、以及歌词还在路上时都上频谱：这一栏不能空两秒，
        // 看起来像加载失败。用户自己翻到频谱那一档也是这里画
        val showSpectrum = pane == Pane.SPECTRUM || (pane == Pane.LYRICS && !showLyrics)
        lyricsView.visibility = if (showLyrics) View.VISIBLE else View.GONE
        spectrum.visibility = if (showSpectrum) View.VISIBLE else View.GONE
        // 原因只在「确实没词」时写：频谱是用户自己要的那一档，不必解释
        val showNote = showSpectrum && lyricLoaded && lyrics.isEmpty()
        lyricsEmpty.visibility = if (showNote) View.VISIBLE else View.GONE
        if (showNote) {
            lyricsEmpty.setText(if (lyricNote != 0) lyricNote else R.string.now_lyrics_none)
        }

        val showList = pane == Pane.LIST
        listHead.visibility = if (showList) View.VISIBLE else View.GONE
        listView.visibility = if (showList) View.VISIBLE else View.GONE
        if (showList) {
            listCount.text = if (listAdapter.itemCount > 0) {
                activity.getString(R.string.now_list_count, listAdapter.itemCount)
            } else {
                ""
            }
        }
        paintPaneButton()
        if (showLyrics) lyricsView.seekTo(playback.snapshot().positionMs)
    }

    /** 图标画的是「按下去会到哪一档」，所以档位和歌词一变就要重画 */
    private fun paintPaneButton() {
        val (icon, label) = when (nextPane(pane)) {
            Pane.LYRICS -> R.drawable.ic_pane_lyrics to R.string.now_pane_lyrics
            Pane.LIST -> R.drawable.ic_nav_queue to R.string.now_list_open
            Pane.SPECTRUM -> R.drawable.ic_pane_spectrum to R.string.now_pane_spectrum
        }
        btnList.setImageResource(icon)
        btnList.contentDescription = activity.getString(label)
    }

    // --------------------------------------------------------- 播放列表

    fun isListOpen(): Boolean = pane == Pane.LIST

    /** 焦点是不是停在右下角档位键上。列表档时按 ↓ 从这儿进曲目表 */
    fun isPaneButtonFocused(): Boolean = btnList.hasFocus()

    /** 焦点在列表里时，上下键归列表自己选歌 */
    fun isListFocused(): Boolean = listView.hasFocus()

    fun isListCloseFocused(): Boolean = listClose.hasFocus()

    /**
     * 焦点是不是停在列表的第一行。
     *
     * 用来判断「上键还能不能再往上走」：已经在第一行了，列表自己走不动，
     * 这时上键就交给页面，把光标送到抬头的叉上——上面本来就只有那一个能按的东西。
     */
    fun listAtTop(): Boolean {
        if (!listView.hasFocus()) return false
        val focused = listView.findFocus() ?: return false
        val pos = listView.getChildAdapterPosition(focused)
        return pos <= 0
    }

    fun focusListClose() {
        listClose.requestFocus()
    }

    /**
     * 收起列表，歌词补位。
     *
     * 必须顺手把光标挪走：列表和抬头一起 GONE，停在里面的焦点就悬空了，
     * 之后按什么键都没有反应。交给打开它的那颗图标——用户是从那儿过来的。
     * 用图标自己收起时这一句是空操作（焦点本来就在它身上）。
     */
    fun closeList() {
        if (pane != Pane.LIST) return
        pane = beforeList
        paintRight()
        btnList.requestFocus()
        Diag.confirm("收起播放列表")
    }

    /**
     * 右下角那一颗：歌词 → 列表 → 频谱 → 歌词 循环。
     * 没词时「歌词」这档不在循环里，于是变成频谱 ⇄ 列表，默认停在频谱。
     */
    fun cyclePane() {
        val from = pane
        val to = nextPane(from)
        // 切档时光标始终留在右下角这颗键上：连按几下就能把三档看完，
        // 中间不该被拽进曲目表。离开列表时更必须主动带走——列表与抬头随即
        // GONE，焦点留在里面会悬空，之后每个方向键都像按在空处。
        // 真要看列表，从档位键按 ↓ 自己下来（playerKey 接管）
        btnList.requestFocus()
        if (to == Pane.LIST) {
            beforeList = from
            fillList()
        }
        userPicked = true
        pane = to
        paintRight()
        Diag.confirm(
            "右栏 → " + when (to) {
                Pane.LYRICS -> "歌词"
                Pane.LIST -> "播放列表"
                Pane.SPECTRUM -> "频谱"
            }
        )
    }

    /**
     * 列表里放什么：合集放这一张的曲目，其余放播放队列。
     *
     * 这两件本来就是同一件事——从合集里起播时，队列就是那些曲目。
     * 分两个入口反而会问出「我现在看的是哪个列表」这种没人答得上的问题。
     */
    private fun fillList() {
        val a = album
        val items = if (a != null && a.isCollection) a.parts else playback.queueSnapshot()
        listAdapter.submit(items)
        listAdapter.markCurrent(track?.cid ?: 0L, playback.snapshot().index)
        listAdapter.playing = playback.isPlaying()
    }

    /**
     * 把焦点钉在「正在播的那一行」。
     *
     * 不能只把焦点交给 RecyclerView：它拿到焦点后会自己挑一行（实测挑到屏幕正中
     * 第 6、7 行），用户翻过来看到的高亮和左栏的歌名对不上。
     *
     * 也不能只 post 一次：这次 post 常常排在布局之前，那时表里还没有任何一行，
     * findViewHolderForAdapterPosition 返回 null，不重试就什么也不会发生。
     */
    fun focusCurrentRow() {
        val at = listAdapter.currentPosition()
        if (at < 0) return
        listLayout.scrollToPositionWithOffset(at, 8)
        var tries = 0
        fun attempt() {
            if (pane != Pane.LIST) return
            val holder = listView.findViewHolderForAdapterPosition(at)
            if (holder != null) {
                val ok = holder.itemView.requestFocus()
                log("列表焦点 → 第 ${at + 1} 行，成功=$ok")
            } else if (tries++ < 6) {
                listView.post { attempt() }
            } else {
                log("列表焦点 → 第 ${at + 1} 行，视图一直没出现")
            }
        }
        listView.post { attempt() }
    }

    /** 列表开着时重填一遍：换歌、合集后到、队列整条换掉都走这里 */
    private fun refreshList() {
        if (pane != Pane.LIST) return
        // 只有人本来就在列表里，才把焦点重新钉到当前行。
        // 否则「歌换了」会把光标从封面/控制行硬拽进列表——人没动，位置自己跑了
        val hadFocus = listView.hasFocus() || listClose.hasFocus()
        fillList()
        paintRight()
        if (hadFocus) focusCurrentRow()
    }

    private fun playFromList(position: Int) {
        val a = album
        if (a != null && a.isCollection) {
            playPart(position)
            return
        }
        // 点到正在播的那一首：不重播。列表里按一下确认，不该把唱到一半的歌倒回去
        if (position == playback.snapshot().index) return
        playback.playAt(position)
        Diag.confirm("列表起播 第${position + 1}首")
    }

    private fun playPart(position: Int) {
        val a = album ?: return
        val picked = a.parts.getOrNull(position) ?: return
        if (picked.cid == track?.cid) return
        val snap = playback.snapshot()
        // 队列已经是这张合集就直接跳下标；否则先把整张装进去
        if (snap.total == a.parts.size) {
            playback.playAt(position)
        } else {
            playback.playQueue(a.parts, position)
        }
        Diag.confirm("曲目起播 第${position + 1}首《${picked.title.take(10)}》")
    }

    /**
     * 把当前这首所在的合集拿到手。
     *
     * 合集内切歌（bvid 没变）直接用现有的表，只挪一下「正在播」那道竖条——
     * 一张 100 P 的专辑听下来，不该打 100 次 view。
     */
    private fun syncCollection(track: Track) {
        if (albumBvid == track.bvid && album != null) {
            listAdapter.markCurrent(track.cid, playback.snapshot().index)
            return
        }
        albumBvid = track.bvid
        album = null
        albumJob?.cancel()
        listAdapter.submit(emptyList())
        if (track.pages <= 1) {
            refreshList()
            return
        }

        // 播放器那边已经在拉了，命中内存就是白捡
        CollectionApi.cached(track.bvid)?.let { applyAlbum(it) }
        albumJob = scope.launch {
            val got = withContext(Dispatchers.IO) {
                runCatching { CollectionApi.load(track.bvid, log) }.getOrNull()
            } ?: return@launch
            if (albumBvid != track.bvid) return@launch
            applyAlbum(got)
        }
    }

    private fun applyAlbum(a: CollectionApi.Album) {
        album = a
        if (a.isCollection) {
            // 合集名以合集为准：列表接口给的 title 有时是 P 名（历史记录就是这样）
            albumView.text = a.title
            albumView.visibility = View.VISIBLE
        }
        refreshList()
    }

    fun focusSeek() {
        seek.requestFocus()
    }

    /** 设置里改过频谱样式或颜色：只重取一次外观，不动这一页的焦点 */
    fun refreshSpectrum() {
        spectrum.applySettings()
    }

    /**
     * 进度上的时间。超过一小时就补上小时位：`4:50` / `6:58:08`。
     *
     * 原来只到分钟，一条六小时五十八分的合集写成 `418:08`——数字没错，但没人这么读时间。
     * 这个版本一整页都是长合集（推荐音乐只收一小时以上的），`mm:ss` 从「偶尔不够用」
     * 变成了「常态不够用」。列表那边（`Track.durationText`）早就是带小时的写法，
     * 这里跟上之后，同一张合集的时长在列表和播放页上终于是同一个字符串。
     */
    private fun fmt(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
