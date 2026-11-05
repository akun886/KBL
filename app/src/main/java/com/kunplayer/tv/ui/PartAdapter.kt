package com.kunplayer.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.kunplayer.tv.R
import com.kunplayer.tv.model.Track

/**
 * 播放列表：正在听的这一串。
 *
 * 装两种东西：一张合集的曲目（从合集里起播时队列就是它），或者播放队列本身。
 * 两种都只有「序号 / 歌名 / 时长」——没有歌手列：合集是一个 UP 主的，
 * 队列是从同一页挑出来的，一百行印同一个名字只会把歌名挤窄。
 *
 * 正在播的那一行画一道红竖条。认法有两级：
 * 有 cid 就按 cid 认（一百个 P 共用同一个 bvid，只有 cid 分得开）；
 * 收藏夹和搜索结果给的 cid 是 0，这时退回队列下标——不退回的话
 * `0 == 0` 会把每一行都画成「正在播」。
 */
class PartAdapter(
    /** 回调里的 position 是曲目表下标，直接对应播放队列 */
    private val onClick: (Int, Track) -> Unit,
) : RecyclerView.Adapter<PartAdapter.PartHolder>() {

    private val parts = ArrayList<Track>()

    private var curCid = 0L
    private var curIndex = -1

    /**
     * 在不在放。暂停时把那一行的小频谱停手——停下来的歌旁边还在跳柱子，
     * 等于告诉用户它还在响。
     */
    var playing: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            val at = currentPosition()
            if (at >= 0) notifyItemChanged(at)
        }

    /** 正在播的是哪一行。换行时只刷受影响的两行——整表重绑会把焦点洗掉 */
    fun markCurrent(cid: Long, index: Int) {
        val was = currentPosition()
        curCid = cid
        curIndex = index
        val now = currentPosition()
        if (was >= 0) notifyItemChanged(was)
        if (now >= 0 && now != was) notifyItemChanged(now)
    }

    /** 正在播的那一行在表里的位置，拿不到就是 -1 */
    fun currentPosition(): Int = when {
        curCid > 0 -> parts.indexOfFirst { it.cid == curCid }
        curIndex in parts.indices -> curIndex
        else -> -1
    }

    fun submit(list: List<Track>) {
        parts.clear()
        parts.addAll(list)
        notifyDataSetChanged()
    }

    private fun isCurrent(track: Track, position: Int): Boolean =
        if (curCid > 0) track.cid == curCid else position == curIndex

    fun isEmpty(): Boolean = parts.isEmpty()

    override fun getItemCount(): Int = parts.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PartHolder =
        PartHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_part, parent, false))

    override fun onBindViewHolder(holder: PartHolder, position: Int) {
        holder.bind(parts[position], position)
    }

    inner class PartHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val sel: View = view.findViewById(R.id.part_sel)
        private val index: TextView = view.findViewById(R.id.part_index)
        private val title: TextView = view.findViewById(R.id.part_title)
        private val dur: TextView = view.findViewById(R.id.part_dur)
        private val spectrum: SpectrumView = view.findViewById(R.id.part_spectrum)

        init {
            spectrum.mode = SpectrumView.Mode.MINI
            view.setOnClickListener {
                val pos = bindingAdapterPosition
                val track = parts.getOrNull(pos) ?: return@setOnClickListener
                onClick(pos, track)
            }
        }

        fun bind(track: Track, position: Int) {
            val current = isCurrent(track, position)
            sel.visibility = if (current) View.VISIBLE else View.INVISIBLE
            spectrum.visibility = if (current) View.VISIBLE else View.GONE
            spectrum.playing = current && playing
            index.text = (position + 1).toString()
            title.text = track.title
            dur.text = track.durationText
        }
    }
}
