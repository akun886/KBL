package com.kunplayer.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.kunplayer.tv.R
import com.kunplayer.tv.net.FeedApi
import com.kunplayer.tv.net.RecommendApi

/**
 * 推荐 UP 主的列表。
 *
 * 和 [TrackListAdapter] 分开写，而不是给曲目行加一堆「如果这是个人」的分支：
 * 两者能对齐的只有「一行一个焦点位」，列却是两套——曲目行有序号和时长，
 * UP 行有头像和已关注。硬塞进一个适配器，最后每个 findViewById 都要判一次类型。
 *
 * 已关注是**批量查回来的一个 mid 集合**（[RecommendApi.followedMids]），
 * 不在绑定里逐行发请求——一屏六行就是六次请求，其中五次纯属浪费，
 * 而且逐条查 `space/acc/info` 正是会被风控盯上的那种用法。
 */
class UpAdapter(
    private val onOpen: (mid: Long, name: String) -> Unit,
    private val onFocus: (RecommendApi.UpEntry) -> Unit = {},
) : RecyclerView.Adapter<UpAdapter.UpHolder>() {

    private val items = ArrayList<RecommendApi.UpEntry>()

    /** 我关注了谁。为空就是一个人都没关注（或没登录），所有标记都不显示 */
    private var followed: Set<Long> = emptySet()

    fun submit(list: List<RecommendApi.UpEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /**
     * 关注关系是后到的（它要联网，而列表先用曲池渲染出来了）。
     * 晚到就把整表刷一次——这里没有「正在播的那行」需要保住焦点以外的状态。
     */
    fun setFollowed(mids: Set<Long>) {
        if (followed == mids) return
        followed = mids
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UpHolder =
        UpHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_up_row, parent, false))

    override fun onBindViewHolder(holder: UpHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class UpHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val face: ImageView = view.findViewById(R.id.up_face)
        private val name: TextView = view.findViewById(R.id.up_name)
        private val songs: TextView = view.findViewById(R.id.up_songs)
        private val followedBadge: TextView = view.findViewById(R.id.up_followed)

        fun bind(up: RecommendApi.UpEntry) {
            name.text = up.name
            songs.text = itemView.context.getString(R.string.up_song_count, up.songs)
            followedBadge.visibility = if (up.mid in followed) View.VISIBLE else View.GONE

            // 头像走和封面同一条 CDN 缩放：原图一两百 KB，40dp 的头像
            // 一屏十几个，压到 120px 才够 A53 撑住
            val url = up.cover
            if (url.isEmpty()) {
                face.setImageDrawable(null)
            } else {
                CoverLoader.load(face, FeedApi.reCrop(url, 120, 120), 120, 120)
            }

            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onFocus(up)
            }
            itemView.setOnClickListener {
                onOpen(up.mid, up.name)
            }
        }
    }
}
