package com.kunplayer.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.kunplayer.tv.R
import com.kunplayer.tv.model.Track

/**
 * 列表的适配器。首页列表与搜索结果共用它。
 *
 * 一行一首歌：序号 + 歌名 + 歌手 + 时长，没有节标题。首页的三个来源
 * （音乐区推荐流、音乐综合榜、分区最新）都不返回分类，既然分不出来就不分——
 * 一列歌里夹着几个不可聚焦的标题，除了把「按几下到第 5 首」变成每次重数，没有别的用处。
 *
 * 序号就是它在队列里的第几位（从 1 起）。分节那一版要同时维护「行上印的号」
 * 和「队列下标」，分节撤掉之后两个数合并回一个，点行直接把这个下标交给播放器。
 *
 * currentCid / currentBvid 只用来画左侧那道红色竖条：正在播的那首即使在列表里，
 * 也要能一眼找回来。它不参与焦点，焦点是「我准备按确认的那一行」。
 *
 * 首选 cid 而不是 bvid：一张 100 P 的合集共用同一个 bvid，按 bvid 标的话
 * 「正在收听」里整张专辑会一起亮起来。
 *
 * 但 cid 有一个坑：**搜索结果没有 cid，全是 0**，而队列里的歌在取到播放地址前
 * cid 也可能是 0。两边都是 0 时 `track.cid == currentCid` 对整张表都成立，
 * 一屏三十行全部亮红条（0.24.0 在「推荐音乐」上撞见过）。所以 cid 为 0 时
 * 退回 bvid 判断——那一列在这个场景里是唯一的。
 */
class TrackListAdapter(
    /** 回调里的 index 就是队列下标，可直接用 */
    private val onClick: (Int, Track) -> Unit,
    /** 点歌手列：进这个 UP 主的主页 */
    private val onArtistClick: (Track) -> Unit = {},
    private val onFocus: (Track) -> Unit = {},
    /**
     * 这一首收藏过没有。
     *
     * 判断走 [com.kunplayer.tv.net.FavState] 的本地索引，绑定里不发请求——
     * 一屏二十行就是二十次，而且「已收藏」只是个提示，不值得为它拖住首屏。
     * 索引没就绪时它返回本地镜像，宁可少标一个心，也不让列表等。
     */
    private val isFav: (Track) -> Boolean = { false },
) : RecyclerView.Adapter<TrackListAdapter.SongHolder>() {

    private val songs = ArrayList<Track>()

    /**
     * 正在播的那一首。只重绘受影响的两行（旧的一行、新的一行），
     * 不用 notifyDataSetChanged：整表重绑会把焦点一起洗掉，
     * 而「自动走到下一首」这件事随时可能在用户翻列表时发生。
     */
    var currentCid: Long = 0L
        set(value) {
            if (field == value) return
            val was = songs.indexOfFirst { isCurrent(it) }
            field = value
            val now = songs.indexOfFirst { isCurrent(it) }
            if (was >= 0) notifyItemChanged(was)
            if (now >= 0 && now != was) notifyItemChanged(now)
        }

    /** cid 拿不到时（搜索来源恒为 0）用来兜底判定的那一首 */
    var currentBvid: String = ""
        set(value) {
            if (field == value) return
            val was = songs.indexOfFirst { isCurrent(it) }
            field = value
            val now = songs.indexOfFirst { isCurrent(it) }
            if (was >= 0) notifyItemChanged(was)
            if (now >= 0 && now != was) notifyItemChanged(now)
        }

    /**
     * 当前这首在不在放。小频谱只在「正在播」的那一行跳，暂停时停手——
     * 停下来的歌旁边还在跳柱子，等于告诉用户它还在响。
     *
     * 只在值真的变了才重画那一行：ticker 每 500ms 会调一次赋值，
     * 每次重绑都会把焦点附近的行刷一遍，滑动时看得出来。
     */
    var currentPlaying: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            val at = songs.indexOfFirst { isCurrent(it) }
            if (at >= 0) notifyItemChanged(at)
        }

    /**
     * 这一行是不是正在播的那一首。
     *
     * cid 优先，为 0 时退回 bvid。**不能只判 cid**：搜索来源的 cid 恒为 0，
     * 队列里还没取到地址的歌 cid 也是 0，两边都是 0 会让整张表都成立。
     */
    private fun isCurrent(t: Track): Boolean =
        if (currentCid != 0L) t.cid == currentCid
        else currentBvid.isNotEmpty() && t.bvid == currentBvid

    /**
     * 适配器内部那份列表本身，**只读**。
     *
     * 换一页是 `clear() + addAll()` 原地改写，所以任何想长期持有它的地方
     * （尤其播放队列）都得自己先拷一份，否则下一次换列表会连着那边一起改。
     */
    val snapshot: List<Track> get() = songs

    val trackCount: Int get() = songs.size

    fun submit(list: List<Track>) {
        songs.clear()
        songs.addAll(list)
        notifyDataSetChanged()
    }

    /** 某首在列表里的位置，用来定位焦点；没有就 -1 */
    fun positionOf(cid: Long): Int {
        if (cid <= 0) return -1
        return songs.indexOfFirst { it.cid == cid }
    }

    /** 按 bvid 找行。合集内 100 首共用一个 bvid，按 cid 找不到时用这个 */
    fun positionOf(bvid: String): Int {
        if (bvid.isEmpty()) return -1
        return songs.indexOfFirst { it.bvid == bvid }
    }

    /**
     * 给某一行补上「合集 N 首」角标。
     *
     * 这一行的 P 数是后来才确认的（view 回来才知道它是合集），
     * 所以只重画这一行，不动整表——整表重绑会把焦点洗掉。
     */
    fun markAlbum(position: Int, pages: Int) {
        if (position !in songs.indices) return
        val t = songs[position]
        if (t.pages == pages) return
        songs[position] = t.copy(pages = pages)
        notifyItemChanged(position)
    }

    override fun getItemCount(): Int = songs.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongHolder =
        SongHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_track_row, parent, false))

    override fun onBindViewHolder(holder: SongHolder, position: Int) {
        holder.bind(songs[position], position)
    }

    inner class SongHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val sel: View = view.findViewById(R.id.row_sel)
        private val index: TextView = view.findViewById(R.id.row_index)
        private val title: TextView = view.findViewById(R.id.row_title)
        private val badge: TextView = view.findViewById(R.id.row_badge)
        private val fav: ImageView = view.findViewById(R.id.row_fav)
        private val artist: TextView = view.findViewById(R.id.row_artist)
        private val dur: TextView = view.findViewById(R.id.row_dur)
        private val spectrum: SpectrumView = view.findViewById(R.id.row_spectrum)

        init {
            spectrum.mode = SpectrumView.Mode.MINI
        }

        /**
         * 行根与歌手列之间横着走。
         *
         * 不用 XML 的 `nextFocusRight`：item 上只要设了 `nextFocus*`，
         * Android 的 FocusFinder 就不再用几何搜索找下一行，
         * RecyclerView 的纵向滚动会整个停摆——一屏六行按不动。
         * 横向这两格是「行内」的关系，本来就该由这一行自己管。
         */
        fun moveHorizontal(dir: Int): Boolean = when (dir) {
            View.FOCUS_RIGHT -> artist.requestFocus()
            View.FOCUS_LEFT -> itemView.requestFocus()
            else -> false
        }

        /** 光标现在停在这一行的哪一格 */
        fun whichColumn(): Int = if (artist.hasFocus()) 1 else 0

        fun bind(track: Track, position: Int) {
            index.text = (position + 1).toString()
            title.text = track.title
            artist.text = track.owner
            dur.text = track.durationText
            val current = isCurrent(track)
            sel.visibility = if (current) View.VISIBLE else View.INVISIBLE
            spectrum.visibility = if (current) View.VISIBLE else View.GONE
            spectrum.playing = current && currentPlaying

            // 合集角标只在「这张合集的头一行」出现。「正在收听」里一张 100 P 的专辑
            // 连着占 100 行，每行都挂一个同样的角标，翻十屏都在说同一句话。
            // 下一行还是同一个 bvid 时就不重复了——列表已经说明了它们在同一个组里。
            val sameAsPrev = position > 0 && songs[position - 1].bvid == track.bvid
            if (track.pages > 1 && !sameAsPrev) {
                badge.visibility = View.VISIBLE
                badge.text = itemView.context.getString(R.string.home_badge_album, track.pages)
            } else {
                badge.visibility = View.GONE
            }

            fav.visibility = if (isFav(track)) View.VISIBLE else View.GONE

            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onFocus(track)
            }
            itemView.setOnClickListener {
                onClick(position, track)
            }
            // 歌手列是独立的一个焦点位：光标能移过去，按确认进他的主页。
            //
            // 这里不判 mid：队列里混着收藏夹来的条目，那些只给作者名字不给 mid，
            // 判了就直接「什么都不做」——用户按了确认，屏幕上一点反应都没有。
            // 缺 mid 时怎么补，交给上层决定（按 bvid 反查一次稿件详情）。
            artist.setOnClickListener {
                onArtistClick(track)
            }
            artist.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onFocus(track)
            }
        }
    }
}
