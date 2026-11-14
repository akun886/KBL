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

    /**
     * 上一次光标停在哪个人身上。
     *
     * 从这一页点进作者主页、再按返回时，人要回到「刚才那一行」——他不是翻了半天
     * 列表才决定点谁的。按 mid 记而不是按下标：列表是按关注时间排的，关注一个新人
     * 之后整张表的下标都会挪位。
     */
    private var focusMid = 0L

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

    /**
     * 从作者主页退回来：把这一页原样还回去，焦点落回出发的那一行。
     *
     * 不重新 load()——那会把整张表重拉一遍、焦点扔回第一行，用户刚看过的人在
     * 十几行之外，等于每次返回都要重新找一遍。手上还有数据就直接摆回去。
     */
    fun resume() {
        if (adapter.itemCount == 0) {
            // 没数据（上次没登录 / 拉失败）：走一遍正常加载
            show()
            return
        }
        panel.visibility = View.VISIBLE
        val at = adapter.indexOf(focusMid).takeIf { it >= 0 } ?: 0
        list.post {
            list.scrollToPosition(at)
            list.post {
                val holder = list.findViewHolderForAdapterPosition(at)
                    ?: list.getChildAt(0)?.let { list.findContainingViewHolder(it) }
                holder?.itemView?.requestFocus()
            }
        }
        Diag.enter("关注列表-返回")
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

        /** 某个 mid 现在在第几行。找不到给 -1，调用方回落到第一行 */
        fun indexOf(mid: Long): Int = items.indexOfFirst { it.mid == mid }

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
            itemView.setOnFocusChangeListener { _, hasFocus ->
                // 进主页再退回来时要钉回这一行，所以每次聚焦都记一下是谁
                if (hasFocus) focusMid = user.mid
            }
            itemView.setOnClickListener {
                Diag.confirm("进入作者主页 ${user.uname}")
                onOpenSpace(user.mid, user.uname)
            }
        }
    }
}
