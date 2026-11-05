package com.kunplayer.tv.ui

import android.app.Activity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kunplayer.tv.R
import com.kunplayer.tv.diag.Diag
import com.kunplayer.tv.model.SpaceUser
import com.kunplayer.tv.model.Track
import com.kunplayer.tv.net.FavState
import com.kunplayer.tv.net.LoginApi
import com.kunplayer.tv.net.UserApi
import com.kunplayer.tv.net.httpsUrl
import com.kunplayer.tv.player.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 作者主页。
 *
 * 上半是「这个人是谁」，下半是「他发了什么」。两件事分开写，
 * 不做成一个头像加一串歌名的流水账——那样就分不清哪个是资料、哪个是内容。
 *
 * 关注按钮是这一页唯一的写操作，放在右上角：它是关于「这个人」的，
 * 不是关于下面某一条投稿的。
 *
 * 投稿点一下就播；收藏是行末那颗心，点心才收藏。
 * 两条路分开，用户不必先想「点一下到底是播放还是收藏」。
 */
class SpacePanel(
    private val activity: Activity,
    private val playback: PlaybackController,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val hint: (String) -> Unit,
    private val onOpenPlayer: () -> Unit,
    /** 面板关掉后把焦点交还首页 */
    private val onClosed: () -> Unit = {},
) {

    private val panel: View = activity.findViewById(R.id.space_panel)
    private val face: ImageView = activity.findViewById(R.id.space_face)
    private val name: TextView = activity.findViewById(R.id.space_name)
    private val sign: TextView = activity.findViewById(R.id.space_sign)
    private val meta: TextView = activity.findViewById(R.id.space_meta)
    private val followBtn: TextView = activity.findViewById(R.id.space_follow)
    private val list: RecyclerView = activity.findViewById(R.id.space_videos)
    private val note: TextView = activity.findViewById(R.id.space_note)

    private val adapter = VideoAdapter()

    private var mid = 0L
    private var followed = false
    private var videos: List<Track> = emptyList()

    val isShowing: Boolean get() = panel.visibility == View.VISIBLE

    init {
        list.layoutManager = ScrollableLayoutManager(activity)
        list.adapter = adapter
        list.itemAnimator = null

        followBtn.setOnClickListener { toggleFollow() }
        // 焦点进出时重刷字色，否则字会停在上一次的状态上
        followBtn.setOnFocusChangeListener { _, hasFocus ->
            paintFollowBtn()
            if (hasFocus) Diag.enter("作者主页 关注按钮")
        }
    }

    fun show(mid: Long, fallbackName: String) {
        this.mid = mid
        panel.visibility = View.VISIBLE
        name.text = fallbackName
        sign.text = ""
        meta.visibility = View.GONE
        note.visibility = View.GONE
        adapter.submit(emptyList())
        loadInfo()
        loadVideos()
        Diag.enter("作者主页")
    }

    fun dismiss() {
        panel.visibility = View.GONE
    }

    /**
     * 浮层关闭必须交还焦点。
     *
     * 这一层的控件随面板一起 GONE，焦点就悬空了——之后所有按键都失灵，
     * 用户看到的是「界面还在但遥控器死了」。所以关掉它时把焦点交回首页，
     * 回到用户出发的地方。
     */
    fun dismissAndReturnFocus() {
        dismiss()
        onClosed()
    }

    /**
     * 从播放页退回这一页时，焦点交回投稿列表。
     *
     * 播放页是盖在作者主页上面的一层，退回来时它自己 GONE，
     * 焦点要是不交还，就悬空在看不见的地方。
     */
    fun focusBack() {
        if (!isShowing) return
        if (adapter.itemCount == 0) {
            followBtn.requestFocus()
            return
        }
        list.post {
            val holder = list.findViewHolderForAdapterPosition(0)
                ?: list.getChildAt(0)?.let { list.findContainingViewHolder(it) }
            holder?.itemView?.requestFocus()
        }
    }

    fun handleKey(code: Int): Boolean {
        if (!isShowing) return false
        if (code == KeyEvent.KEYCODE_BACK) {
            dismissAndReturnFocus()
            return true
        }
        // 投稿列表第一行 ⇄ 关注按钮，这一段自己钉死。
        //
        // 不钉的话，「上」会落到 FocusFinder 的几何搜索上：关注按钮在右上角、
        // 投稿行在左边，几何上够不着，于是走 RecyclerView 的焦点搜索兜底去补
        // 上一行——作者主页只有一个列表，补不出来，还顺带崩过一次
        // （IllegalArgumentException: parameter must be a descendant of this view）。
        if (code == KeyEvent.KEYCODE_DPAD_UP && inFirstVideoRow()) {
            followBtn.requestFocus()
            return true
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN && followBtn.hasFocus()) {
            focusFirstVideo()
            return true
        }
        return false
    }

    /** 光标是不是停在投稿列表的第一行 */
    private fun inFirstVideoRow(): Boolean {
        var view: View? = activity.currentFocus
        while (view != null) {
            val holder = list.findContainingViewHolder(view)
            if (holder != null) return holder.adapterPosition == 0
            view = view.parent as? View
        }
        return false
    }

    private fun focusFirstVideo() {
        if (adapter.itemCount == 0) return
        list.scrollToPosition(0)
        // 列表可能还没布局完，拿不到 holder；post 一层再钉
        list.post {
            list.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    // ---------------------------------------------------------------- 资料

    private fun loadInfo() {
        scope.launch {
            val got = withContext(Dispatchers.IO) {
                runCatching { UserApi.spaceInfo(mid, log) }
                    .onFailure { log("作者资料失败 ${it.message}") }
                    .getOrNull()
            }
            if (!isShowing || got == null) return@launch
            paintInfo(got)
        }
    }

    private fun paintInfo(info: SpaceUser) {
        name.text = info.name.ifEmpty { name.text.toString() }
        sign.text = info.sign
        sign.visibility = if (info.sign.isEmpty()) View.GONE else View.VISIBLE
        val level = info.level
        if (level > 0) {
            meta.text = activity.getString(R.string.space_level, level)
            meta.visibility = View.VISIBLE
        } else {
            meta.visibility = View.GONE
        }
        CoverLoader.load(face, info.face.httpsUrl(), 176, 176)
        followed = info.isFollowed
        paintFollowBtn()
    }

    private fun paintFollowBtn() {
        followBtn.setText(if (followed) R.string.space_followed else R.string.space_follow)
        followBtn.setBackgroundResource(
            if (followed) R.drawable.bg_pill_dark else R.drawable.bg_pill_play,
        )
        // 字色必须跟着背景翻：
        // 已关注是深色胶囊配白字，但胶囊一聚焦背景就翻成白色（bg_pill_dark 的
        // focused 项），白字压在白底上——按钮上的字整个消失，只剩一个白块。
        val onWhite = !followed || followBtn.hasFocus()
        followBtn.setTextColor(
            if (onWhite) 0xFF000000.toInt() else activity.getColor(R.color.text_primary),
        )
    }

    private fun toggleFollow() {
        if (mid <= 0) return
        if (!LoginApi.isLoggedIn()) {
            hint(activity.getString(R.string.follow_need_login))
            return
        }
        val next = !followed
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { UserApi.setFollow(mid, next, log) }
                    .onFailure { log("关注操作失败 ${it.message}") }
            }
            outcome.onSuccess {
                followed = next
                paintFollowBtn()
                Diag.confirm(if (next) "关注作者" else "取消关注")
            }.onFailure {
                hint(activity.getString(R.string.fav_folder_failed, it.message ?: ""))
            }
        }
    }

    // ---------------------------------------------------------------- 投稿

    private fun loadVideos() {
        scope.launch {
            val got = withContext(Dispatchers.IO) {
                runCatching { UserApi.spaceVideos(mid, 1, log) }
                    .onFailure { log("作者投稿失败 ${it.message}") }
                    .getOrNull()
            }
            if (!isShowing) return@launch
            if (got == null) {
                note.setText(R.string.space_failed)
                note.visibility = View.VISIBLE
                return@launch
            }
            videos = got
            adapter.submit(got)
            note.text = if (got.isEmpty()) activity.getString(R.string.space_no_video) else ""
            note.visibility = if (got.isEmpty()) View.VISIBLE else View.GONE
            // 收藏心要按云端的收藏状态画，所以先把索引拉起来
            scope.launch {
                withContext(Dispatchers.IO) { FavState.ensureIndexed(activity, log) }
                if (isShowing) adapter.notifyDataSetChanged()
            }
            list.post {
                val holder = list.findViewHolderForAdapterPosition(0)
                    ?: list.getChildAt(0)?.let { list.findContainingViewHolder(it) }
                holder?.itemView?.requestFocus()
            }
        }
    }

    private fun play(position: Int) {
        val track = videos.getOrNull(position) ?: return
        playback.playQueue(videos.toList(), position)
        Diag.confirm("作者投稿起播《${track.title.take(12)}》")
        onOpenPlayer()
    }

    private fun toggleFavorite(position: Int) {
        val track = videos.getOrNull(position) ?: return
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { FavState.toggle(activity, track, log) }
                    .onFailure { log("收藏失败 ${it.message}") }
            }
            outcome.onSuccess { added ->
                // 只刷这一行，不能 notifyDataSetChanged()。
                // 整表重绑会把焦点一起洗掉：收藏完再按一下，按键直接穿透到
                // 浮层背后的首页侧栏——实测人被带回「音乐」页，收藏还在，
                // 但人已经不在这一页了。
                adapter.notifyItemChanged(position)
                // 重绑后把焦点钉回这颗心：万一行被重建了，光标也不能丢
                list.post {
                    val holder = list.findViewHolderForAdapterPosition(position) as? VideoHolder
                    holder?.focusFav()
                }
                hint(activity.getString(if (added) R.string.fav_added else R.string.fav_removed))
            }.onFailure {
                hint(activity.getString(R.string.fav_failed))
            }
        }
    }

    // ---------------------------------------------------------------- 列表

    private inner class VideoAdapter : RecyclerView.Adapter<VideoHolder>() {
        private val items = ArrayList<Track>()

        fun submit(list: List<Track>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoHolder =
            VideoHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_space_video, parent, false),
            )

        override fun onBindViewHolder(holder: VideoHolder, position: Int) {
            holder.bind(items[position], position)
        }
    }

    private inner class VideoHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.space_video_title)
        private val fav: ImageView = view.findViewById(R.id.space_video_fav)
        private val dur: TextView = view.findViewById(R.id.space_video_dur)

        fun bind(track: Track, position: Int) {
            title.text = track.title
            dur.text = track.durationText
            val on = FavState.isFavorited(activity, track.bvid)
            fav.setImageResource(if (on) R.drawable.ic_heart_on else R.drawable.ic_heart)
            fav.imageTintList =
                if (on) null else activity.resources.getColorStateList(R.color.text_secondary, activity.theme)
            itemView.setOnClickListener { play(position) }
            fav.setOnClickListener { toggleFavorite(position) }
        }

        /** 收藏刷新后把光标钉回这颗心 */
        fun focusFav() {
            fav.requestFocus()
        }
    }
}
