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
import com.kunplayer.tv.model.FollowUser
import com.kunplayer.tv.net.LoginApi
import com.kunplayer.tv.net.UserApi
import com.kunplayer.tv.net.httpsUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 关注列表。侧栏「关注」点进来就是这一页。
 *
 * 一屏只列人：点谁进谁的主页，主页里再听他的歌。
 * 在这里就把他的歌铺出来会变成第二张歌单，两处都摆就分不清
 * 「关注」和「播放历史」到底差在哪。
 *
 * 头像要留着：昵称会重名也会被改，头像是认人的唯一依据。
 */
class FollowingPanel(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    /** 点谁就进谁的主页 */
    private val onOpenSpace: (Long, String) -> Unit,
    /** 面板关掉后把焦点交还首页 */
    private val onClosed: () -> Unit = {},
) {

    private val panel: View = activity.findViewById(R.id.following_panel)
    private val list: RecyclerView = activity.findViewById(R.id.following_list)
    private val note: TextView = activity.findViewById(R.id.following_note)

    private val adapter = UserAdapter()

    val isShowing: Boolean get() = panel.visibility == View.VISIBLE

    init {
        list.layoutManager = ScrollableLayoutManager(activity)
        list.adapter = adapter
        list.itemAnimator = null
    }

    fun show() {
        panel.visibility = View.VISIBLE
        note.setText(R.string.home_loading)
        load()
        Diag.enter("关注列表")
    }

    fun dismiss() {
        panel.visibility = View.GONE
    }

    /**
     * 浮层关闭必须交还焦点。
     *
     * 面板里的行随面板一起 GONE，焦点就悬空了——之后所有按键都失灵。
     */
    fun dismissAndReturnFocus() {
        dismiss()
        onClosed()
    }

    fun handleKey(code: Int): Boolean {
        if (!isShowing) return false
        if (code == KeyEvent.KEYCODE_BACK) {
            dismissAndReturnFocus()
            return true
        }
        return false
    }

    private fun load() {
        if (!LoginApi.isLoggedIn()) {
            note.setText(R.string.follow_need_login)
            adapter.submit(emptyList())
            return
        }
        scope.launch {
            val got = withContext(Dispatchers.IO) {
                runCatching { UserApi.followings(1, log) }
                    .onFailure { log("关注列表失败 ${it.message}") }
                    .getOrNull()
            }
            if (!isShowing) return@launch
            if (got == null) {
                note.setText(R.string.follow_failed)
                return@launch
            }
            adapter.submit(got)
            note.text = if (got.isEmpty()) activity.getString(R.string.follow_empty) else ""
            list.post {
                val holder = list.findViewHolderForAdapterPosition(0)
                    ?: list.getChildAt(0)?.let { list.findContainingViewHolder(it) }
                holder?.itemView?.requestFocus()
            }
        }
    }

    private inner class UserAdapter : RecyclerView.Adapter<UserHolder>() {
        private val items = ArrayList<FollowUser>()

        fun submit(list: List<FollowUser>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UserHolder =
            UserHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_following, parent, false),
            )

        override fun onBindViewHolder(holder: UserHolder, position: Int) {
            holder.bind(items[position])
        }
    }

    private inner class UserHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val face: ImageView = view.findViewById(R.id.following_face)
        private val name: TextView = view.findViewById(R.id.following_name)
        private val sign: TextView = view.findViewById(R.id.following_sign)

        fun bind(user: FollowUser) {
            name.text = user.uname
            sign.text = user.sign
            sign.visibility = if (user.sign.isEmpty()) View.GONE else View.VISIBLE
            CoverLoader.load(face, user.face.httpsUrl(), 80, 80)
            itemView.setOnClickListener {
                Diag.confirm("进入作者主页 ${user.uname}")
                onOpenSpace(user.mid, user.uname)
            }
        }
    }
}
