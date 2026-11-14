package com.kunplayer.tv.ui

import android.app.Activity
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.kunplayer.tv.R
import com.kunplayer.tv.cache.SavedAccount

/**
 * 切换账号浮层。
 *
 * 「切换」不是「退出再登」：换一个小号听歌，回来还得再扫一次码，这件事做两次就没人愿意做了。
 * 所以这里列的是登录过的账号，选中即换（Cookie 整套替换，见 `LoginApi.adopt`），
 * 最后一行才是「扫码登录新账号」——那是加人，不是换人。
 *
 * 行是动态建的：账号个数不定、头像要异步加载，写在布局里就得预置固定几行再藏。
 * 焦点由本类自己管，越界一律吃掉：卡片外面就是遮罩，放行的话焦点会落到一层不画高亮的东西上，
 * 之后每个方向键都像按在空处。
 */
class AccountSwitchPanel(
    private val activity: Activity,
    private val onPick: (SavedAccount) -> Unit,
    private val onScanNew: () -> Unit,
    /** 返回/取消时把焦点交回入口那颗「切换账号」 */
    private val onClosed: () -> Unit,
) {

    private val overlay: View = activity.findViewById(R.id.switch_overlay)
    private val list: LinearLayout = activity.findViewById(R.id.switch_list)
    private val qrRow: TextView = activity.findViewById(R.id.switch_qr)

    private val rows = mutableListOf<View>()

    /** 当前登录的那个账号在 rows 里的位置。-1 = 没有（未登录 / 档案里没有） */
    private var currentIndex = -1

    var isShowing = false
        private set

    init {
        // 遮罩本身是焦点宿主，点它不做事：卡片以外的空白处按一下不该发生任何事，
        // 但也不能让焦点落上去（那一层没有视觉反馈）
        overlay.setOnClickListener { }
        qrRow.setOnClickListener { onScanNew() }
    }

    fun show(accounts: List<SavedAccount>, currentMid: Long) {
        if (isShowing) return
        build(accounts, currentMid)
        overlay.visibility = View.VISIBLE
        isShowing = true
        // 落在当前账号那一行：多数人开这个面板是为了「换回上次那个」，
        // 光标停在列表顶上还要再按一下才知道自己在哪儿
        val target = rows.getOrNull(currentIndex) ?: rows.firstOrNull()
        target?.post { target.requestFocus() }
    }

    fun dismiss() {
        if (!isShowing) return
        isShowing = false
        overlay.visibility = View.GONE
        onClosed()
    }

    /**
     * 按键。只认返回和方向键：
     * 上是上一行、下是下一行，越界吃掉；左右没有内容，也吃掉。
     * 其余（确认键）交给行自己——行是 clickable，回车会走 onClickListener。
     */
    fun handleKey(code: Int): Boolean {
        if (!isShowing) return false
        if (code == KeyEvent.KEYCODE_BACK) {
            dismiss()
            return true
        }
        val cur = rows.indexOfFirst { it == activity.currentFocus }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            return true
        }
        if (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN) return false
        // 焦点不在任何一行（刚打开、或落在遮罩上）：往它该去的方向补一格
        if (cur < 0) {
            (rows.getOrNull(currentIndex) ?: rows.firstOrNull())?.requestFocus()
            return true
        }
        val next = if (code == KeyEvent.KEYCODE_DPAD_UP) cur - 1 else cur + 1
        val target = rows.getOrNull(next)
        if (target != null) {
            target.requestFocus()
        } else {
            // 已经在顶/底：停在原地。放行会让焦点摸到卡片外面去
            rows[cur].requestFocus()
        }
        return true
    }

    private fun build(accounts: List<SavedAccount>, currentMid: Long) {
        list.removeAllViews()
        rows.clear()
        currentIndex = -1

        accounts.forEach { acc ->
            val current = acc.mid == currentMid
            val row = accountRow(acc, current)
            list.addView(row)
            if (current) currentIndex = rows.size
            rows += row
        }
        // 一个存档都没有（第一次登录、或刚升上来的老版本）：列表区就是空的，
        // 只剩下面那颗「扫码登录新账号」，这是对的状态，不写「还没有账号」这种话
        rows += qrRow
    }

    /**
     * 一行账号。
     *
     * 字色由这一行的焦点监听器直接改，不用 `duplicateParentState` + color selector：
     * 那个属性只跟**直接父级**联动，而昵称和身份在 `col` 里隔了一层，`col` 自己不是焦点宿主，
     * 状态传不下来——白底上留一行白字，昵称整块看不见（0.32.0 第一次真机就是这样）。
     * 隔层再套 `duplicateParentState` 要靠每一层都刷新状态，链条一断就退化，不如直接写明。
     */
    private fun accountRow(acc: SavedAccount, current: Boolean): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = activity.getDrawable(R.drawable.bg_account_row)
            isFocusable = true
            isClickable = true
            isFocusableInTouchMode = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) }
        }

        val face = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            background = activity.getDrawable(R.drawable.bg_album)
            clipToOutline = true
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = null
        }
        if (acc.face.isNotEmpty()) {
            CoverLoader.load(face, acc.face, 96, 96)
        } else {
            // 没头像就整块收起来，空圆比没有更像缺图
            face.visibility = View.GONE
        }
        row.addView(face)

        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(12) }
        }
        val title = TextView(activity).apply {
            text = acc.uname.ifEmpty { activity.getString(R.string.account_guest) }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        col.addView(title)
        var sub: TextView? = null
        if (acc.vip.isNotEmpty()) {
            sub = TextView(activity).apply {
                text = acc.vip
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            }
            col.addView(sub)
        }
        row.addView(col)

        var badge: TextView? = null
        if (current) {
            badge = TextView(activity).apply {
                text = activity.getString(R.string.account_switch_current)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(8), 0, 0, 0)
            }
            row.addView(badge)
        }

        // 常态：白字 + 次要灰；聚焦：整行翻白，字全部压成深色。
        // 三个 TextView 一起翻，漏一个就是白底白字
        fun paint(focused: Boolean) {
            title.setTextColor(if (focused) INK else activity.getColor(R.color.text_primary))
            sub?.setTextColor(if (focused) INK_SUB else activity.getColor(R.color.text_secondary))
            badge?.setTextColor(if (focused) INK_SUB else activity.getColor(R.color.text_secondary))
        }
        paint(false)
        row.setOnFocusChangeListener { _, has -> paint(has) }

        row.setOnClickListener { onPick(acc) }
        return row
    }

    private companion object {
        /** 白底上的主文字。纯黑在电视上发死，留一点点余量 */
        const val INK = 0xFF0A0A0A.toInt()

        /** 白底上的次要文字。70% 黑，三米外仍读得出「大会员已过期」 */
        const val INK_SUB = 0xB3000000.toInt()
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()
}
