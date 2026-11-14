package com.kunplayer.tv.ui

import android.app.Activity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kunplayer.tv.R
import com.kunplayer.tv.diag.Diag
import com.kunplayer.tv.model.FavFolder
import com.kunplayer.tv.net.CloudFav
import com.kunplayer.tv.net.FavApi
import com.kunplayer.tv.net.FavState
import com.kunplayer.tv.net.LoginApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 收藏夹管理：新建与删除。
 *
 * 「我的收藏」页读的是 B 站的云端收藏夹，所以这一页管的是同一份东西——
 * 新建的夹子下一刷新就出现在收藏页里，删掉的当场消失。
 *
 * 删除是两步：第一次按确认把那一行改成「再按一次确认删除《X》」，
 * 第二次才真删。收藏夹里的东西删了找不回来，电视上又没有「撤销」，
 * 一步到位太危险，两步是这里唯一说得过去的做法。
 */
class FavFolderPanel(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit,
    private val hint: (String) -> Unit,
    /** 收藏夹变了：首页那一页要重拉 */
    private val onChanged: () -> Unit,
    /** 面板关掉后把焦点交还首页 */
    private val onClosed: () -> Unit = {},
) {

    private val panel: View = activity.findViewById(R.id.fav_folder_panel)
    private val heading: TextView = activity.findViewById(R.id.fav_folder_heading)
    private val createBox: View = activity.findViewById(R.id.fav_folder_create)
    private val input: ImeEditText = activity.findViewById(R.id.fav_folder_input)
    private val okBtn: TextView = activity.findViewById(R.id.fav_folder_ok)
    private val cancelBtn: TextView = activity.findViewById(R.id.fav_folder_cancel)
    private val delBox: View = activity.findViewById(R.id.fav_folder_del)
    private val list: RecyclerView = activity.findViewById(R.id.fav_folder_list)
    private val note: TextView = activity.findViewById(R.id.fav_folder_note)
    private val delCancel: TextView = activity.findViewById(R.id.fav_folder_del_cancel)

    private val adapter = FolderAdapter()

    /** 输入法键盘此刻是不是挂着。BACK 第一次只收它，第二次才关面板 */
    private var imeShown = false

    /** 正在等第二次确认的收藏夹 id */
    private var pendingId = 0L

    /** 默认收藏夹不能删。B 站每个账号都有它 */
    private var defaultId = 0L

    val isShowing: Boolean get() = panel.visibility == View.VISIBLE

    init {
        list.layoutManager = ScrollableLayoutManager(activity)
        list.adapter = adapter
        list.itemAnimator = null

        okBtn.setOnClickListener { submitCreate() }
        cancelBtn.setOnClickListener { dismiss() }
        delCancel.setOnClickListener { dismiss() }
        // 输入法的回车 = 确定。电视输入法的回车不一定带 actionId，一并认 UNSPECIFIED
        input.setOnEditorActionListener { _, actionId, _ ->
            val done = actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_UNSPECIFIED ||
                actionId == EditorInfo.IME_ACTION_GO
            if (done) submitCreate()
            done
        }
        // 输入法会把 BACK 先吞掉（实测要连按三次才轮到宿主），
        // onKeyPreIme 是它之前那道关口：第一次收键盘，第二次关面板。
        // 收键盘时把焦点交给「确定」：留在输入框里的话搜狗会把输入焦点再抢回去，
        // 下一次 BACK 又去收一个已经收掉的键盘，按几下都不关
        input.onBack = {
            if (imeShown) {
                okBtn.requestFocus()
                hideIme()
                true
            } else {
                dismissAndReturnFocus()
                true
            }
        }
        // 键盘挂着时方向键先被输入法吃掉（它拿去选候选），handleKey 收不到。
        // 往下走到「确定」只能在这道关口上接
        input.onDpad = { code ->
            when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    okBtn.requestFocus()
                    true
                }
                KeyEvent.KEYCODE_DPAD_UP -> true
                else -> false
            }
        }
        // 焦点回到输入框就把键盘叫出来：从「确定」按回上一行是还想接着打。
        // 走的时候要收——键盘立起来正好盖住底部那两颗按钮
        input.setOnFocusChangeListener { _, has ->
            if (has) {
                input.post {
                    imeShown = true
                    imm()?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                }
            } else {
                hideIme()
            }
        }
    }

    private fun imm(): InputMethodManager? =
        activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as? InputMethodManager

    private fun hideIme() {
        imeShown = false
        imm()?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    // ---------------------------------------------------------------- 打开

    /** 新建收藏夹 */
    fun showCreate() {
        pendingId = 0L
        panel.visibility = View.VISIBLE
        heading.setText(R.string.fav_folder_new_title)
        createBox.visibility = View.VISIBLE
        delBox.visibility = View.GONE
        input.setText("")
        // 进来就把键盘带出来：这一页唯一要做的第一件事就是打夹名
        input.post {
            input.requestFocus()
            imeShown = true
            imm()?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        Diag.enter("新建收藏夹")
    }

    /** 删除收藏夹 */
    fun showDelete() {
        pendingId = 0L
        // 从「新建」切过来时键盘可能还开着，这一档没有输入框，得收掉
        hideIme()
        panel.visibility = View.VISIBLE
        heading.setText(R.string.fav_folder_del_title)
        createBox.visibility = View.GONE
        delBox.visibility = View.VISIBLE
        note.setText(R.string.home_loading)
        loadFolders()
        Diag.enter("删除收藏夹")
    }

    fun dismiss() {
        // 面板 GONE 不会收掉系统键盘，它会孤零零留在屏幕上
        hideIme()
        panel.visibility = View.GONE
        pendingId = 0L
    }

    /**
     * 浮层关闭必须交还焦点。
     *
     * 面板里的控件随面板一起 GONE，焦点就悬空了——之后所有按键都失灵。
     */
    fun dismissAndReturnFocus() {
        dismiss()
        onClosed()
    }

    /**
     * 返回键归这一层管：面板开着的时候返回是「关面板」，不是「退出应用」
     *
     * 输入框里那几颗方向键也归这一层：单行 EditText 会把 ↑↓ 当成光标移动吃掉，
     * 遥控器一路按到屏幕底也出不了输入框。
     */
    fun handleKey(code: Int): Boolean {
        if (!isShowing) return false
        if (code == KeyEvent.KEYCODE_BACK) {
            // 键盘开着：先收键盘（焦点交给「确定」），再按一次才关面板——一次返回只退一层
            if (imeShown) {
                okBtn.requestFocus()
                hideIme()
                return true
            }
            dismissAndReturnFocus()
            return true
        }
        // 删除模式里没有输入框，方向键全归列表
        if (createBox.visibility != View.VISIBLE) return false
        if (activity.currentFocus === input) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    okBtn.requestFocus()
                    return true
                }
                // 输入框上面是那一行标题，没有可聚焦的东西：吃掉，
                // 放行的话焦点会跑到浮层背后的首页去
                KeyEvent.KEYCODE_DPAD_UP -> return true
            }
            return false
        }
        // 「确定 / 取消」往上回输入框
        if (code == KeyEvent.KEYCODE_DPAD_UP &&
            (activity.currentFocus === okBtn || activity.currentFocus === cancelBtn)
        ) {
            input.requestFocus()
            return true
        }
        return false
    }

    // ---------------------------------------------------------------- 新建

    private fun submitCreate() {
        val name = input.text?.toString().orEmpty().trim()
        if (name.isEmpty()) {
            hint(activity.getString(R.string.fav_folder_hint))
            return
        }
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { CloudFav.create(name, log) }
                    .onFailure { log("建夹失败 ${it.message}") }
            }
            outcome.onSuccess { created ->
                hint(activity.getString(R.string.fav_folder_created, created.title))
                Diag.confirm("新建收藏夹《${created.title}》")
                // 新夹子是空的，但索引得知道它存在——否则往里收藏会写进一个不存在的夹
                FavState.invalidate()
                onChanged()
                dismiss()
            }.onFailure {
                hint(activity.getString(R.string.fav_folder_failed, it.message ?: ""))
            }
        }
    }

    // ---------------------------------------------------------------- 删除

    private fun loadFolders() {
        scope.launch {
            val got = withContext(Dispatchers.IO) {
                runCatching { FavApi.folders(LoginApi.mid(), log) }.getOrNull()
            }
            if (!isShowing) return@launch
            if (got == null) {
                note.setText(R.string.fav_folder_failed)
                return@launch
            }
            // 默认收藏夹不能删。B 站每个账号都有它，名字固定是「默认收藏夹」
            defaultId = got.firstOrNull { it.title.contains("默认") }?.id
                ?: got.firstOrNull { it.attr == 0 }?.id
                ?: got.firstOrNull()?.id
                ?: 0L
            // 焦点之前落在列表的哪一行：重载后要停回原处。
            // 必须在 submit 之前取——整表重绑之后焦点就丢了
            val keep = focusedPosition()
            adapter.submit(got)
            note.text = if (got.isEmpty()) {
                activity.getString(R.string.fav_cloud_empty)
            } else {
                ""
            }
            list.post {
                // 停在原来那一行，别跳回第一行：
                // 用户刚删完一条，焦点却跑到列表顶上，等于把刚才的操作抹掉了
                val position = keep.coerceIn(0, (adapter.itemCount - 1).coerceAtLeast(0))
                focusRow(position)
            }
        }
    }

    /**
     * 把焦点放到列表的第几行。
     *
     * post 两次是有原因的：第一次 post 时 RecyclerView 还没完成布局，
     * 那一行还不存在，requestFocus 拿到的 holder 是 null，
     * 焦点就落到了面板里唯一抢得到的「取消」按钮上——
     * 表现是「打开删除面板，焦点在取消上」，用户按确认只会关掉面板。
     */
    private fun focusRow(position: Int) {
        list.post {
            val holder = list.findViewHolderForAdapterPosition(position)
                ?: list.getChildAt(0)?.let { list.findContainingViewHolder(it) }
            if (holder != null) {
                holder.itemView.requestFocus()
            } else {
                // 还没布局好，下一轮再试
                list.post { focusRow(position) }
            }
        }
    }

    /**
     * 焦点在列表的第几行。
     *
     * 找不到（比如列表刚打开，焦点被「取消」按钮抢走了）就返回 -1，
     * 让调用方决定是停在第一行还是原位。
     */
    private fun focusedPosition(): Int {
        val focused = list.findFocus() ?: return -1
        val holder = list.findContainingViewHolder(focused) ?: return -1
        return holder.bindingAdapterPosition
    }

    private fun pick(folder: FavFolder) {
        if (folder.id == defaultId) {
            hint(activity.getString(R.string.fav_folder_locked))
            return
        }
        if (pendingId != folder.id) {
            pendingId = folder.id
            // 只重绑这一行，不要 notifyDataSetChanged：
            // 整表重绑会把焦点一起洗掉，用户第二次按确认就打在了别的行上
            adapter.refreshOne(folder.id)
            // 重绑的瞬间焦点可能短暂掉到列表外，钉回这一行，
            // 否则第二次确认就落空了
            list.post {
                val holder = list.findViewHolderForAdapterPosition(
                    adapter.positionOf(folder.id),
                )
                holder?.itemView?.requestFocus()
            }
            Diag.confirm("待删《${folder.title}》")
            return
        }
        pendingId = 0L
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { CloudFav.delete(listOf(folder.id), log) }
                    .onFailure { log("删夹失败 ${it.message}") }
            }
            outcome.onSuccess {
                hint(activity.getString(R.string.fav_folder_deleted, folder.title))
                Diag.confirm("删除收藏夹《${folder.title}》")
                FavState.invalidate()
                onChanged()
                loadFolders()
            }.onFailure {
                hint(activity.getString(R.string.fav_folder_failed, it.message ?: ""))
                adapter.refreshOne(folder.id)
            }
        }
    }

    // ---------------------------------------------------------------- 列表

    private inner class FolderAdapter : RecyclerView.Adapter<FolderHolder>() {
        private val items = ArrayList<FavFolder>()

        fun submit(list: List<FavFolder>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        /** 某个收藏夹的标题变了：只重绑那一行 */
        fun refreshOne(id: Long) {
            val position = items.indexOfFirst { it.id == id }
            if (position >= 0) notifyItemChanged(position)
        }

        fun positionOf(id: Long): Int = items.indexOfFirst { it.id == id }
        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderHolder =
            FolderHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_fav_folder, parent, false),
            )

        override fun onBindViewHolder(holder: FolderHolder, position: Int) {
            holder.bind(items[position])
        }
    }

    private inner class FolderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.fav_folder_title)
        private val count: TextView = view.findViewById(R.id.fav_folder_count)

        fun bind(folder: FavFolder) {
            val pending = pendingId == folder.id
            title.text = if (pending) {
                activity.getString(R.string.fav_folder_confirm, folder.title)
            } else {
                folder.title
            }
            count.text = when {
                pending -> ""
                folder.id == defaultId -> activity.getString(R.string.fav_folder_locked)
                else -> activity.getString(R.string.fav_folder_count, folder.mediaCount)
            }
            itemView.setOnClickListener { pick(folder) }
        }
    }
}
