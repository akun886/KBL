package com.kunplayer.tv.ui

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 列表的纵向导航帮手。
 *
 * 为什么不能交给系统的焦点搜索，两个原因：
 *
 * 一、RecyclerView 只创建可见范围内的行。下一行还没被创建出来时，
 * 焦点搜索扑空；系统虽然会在 `onFocusSearchFailed` 里补出那一行
 * （LinearLayoutManager 内部的 `fill()`），补出来的行却排在列表底边外面，
 * 焦点移过去了用户看不见——光标像是凭空消失了。
 *
 * 二、更要命的是底栏就贴在列表正下方。焦点搜索在整棵树里找「下面」，
 * 底栏比列表里补出来的那一行更近，于是焦点直接跳到底栏，
 * 中间那一屏歌被整个跳过。
 *
 * 所以纵向翻页自己来：算出目标行 → 滚过去 → 把焦点钉上去。
 * 横向（行内从歌名到歌手列）也一样，见 [HomeStage.moveWithinRow]。
 *
 * 用的是 RecyclerView 1.3 的老签名（方向是 int 常量），
 * 不是 1.4 之后的 `FocusDirection`——那套 API 26 才有，这台电视是 API 23。
 */
class ScrollableLayoutManager(context: Context) : LinearLayoutManager(context) {

    /**
     * 要去的那一行。
     *
     * 用来在两次按键之间记住进度：滚动是异步的，聚焦要等布局走完才落得上，
     * 这中间再来按键，从焦点读到的还是旧行，步进就丢了一格——
     * 实测按八次下键，焦点只走了四行。
     */
    private var pending: Int = RecyclerView.NO_POSITION

    /** 当前有焦点的那个 view 在列表里的位置 */
    fun focusedPosition(focused: View?): Int {
        // 有待办时以待办为准，见上面 pending 的说明
        if (pending != RecyclerView.NO_POSITION) return pending
        if (focused == null) return RecyclerView.NO_POSITION
        val list = focused.parent as? RecyclerView ?: return RecyclerView.NO_POSITION
        val holder = list.findContainingViewHolder(focused) ?: return RecyclerView.NO_POSITION
        return holder.bindingAdapterPosition
    }

    /**
     * 翻到第几行。
     *
     * 这里刻意不用 `RecyclerView.scrollBy`：在这个上下文里它是空操作。
     * 实测连滚六次，可见范围始终 `0..5 → 0..5`，一行都没动过——
     * 于是第 7 行永远不会被创建，`findViewHolderForAdapterPosition` 拿到 null，
     * 焦点就卡在可见的最后一行上。日志里 `from` 一路涨到 7 是 `pending`
     * 在记账，不是焦点真的走了七行。
     *
     * 改用 `scrollToPositionWithOffset`：它走 requestLayout 的正式流程，
     * 一定推得动列表。
     *
     * 滚多少很关键：**目标行还看得见就一点都不滚**，光标才会在屏幕上
     * 一格一格往下走。只有它真的出了可见区，才把列表滚到「刚好让它露出来」——
     * 往下贴底、往上贴顶。反过来做（每次把目标行摆到来源行原来的位置）
     * 会让来源行永远贴顶，于是每按一次都在把目标行顶到第一行：
     * 光标一动不动，内容自己在后面滚。
     *
     * @param from 光标现在在哪一行
     * @param to 要去哪一行
     */
    fun moveTo(list: RecyclerView, from: Int, to: Int): Boolean {
        if (to < 0 || to >= itemCount) return false
        pending = to

        val existing = findViewByPosition(to)
        if (existing == null || !fullyVisible(list, existing)) {
            // 往下：贴底露出，光标停在末行，内容一行行往上走
            // 往上：贴顶露出，光标停在首行
            val dst = if (to > from) {
                (list.height - list.paddingTop - list.paddingBottom - rowHeight()).coerceAtLeast(0)
            } else {
                0
            }
            scrollToPositionWithOffset(to, dst)
        }

        // 两层 post：第一层等布局走完，第二层再确认那一行真的被创建出来了
        list.post {
            list.post {
                val holder = list.findViewHolderForAdapterPosition(to)
                if (holder == null) {
                    // 兜底：极端情况下（快速连按、行还没测量完）这一行仍不存在，
                    // 再滚一次，这次贴顶——宁可多滚一点，也不能让光标消失
                    scrollToPositionWithOffset(to, 0)
                    list.post {
                        if (list.findViewHolderForAdapterPosition(to)?.itemView?.requestFocus() == true) {
                            pending = RecyclerView.NO_POSITION
                        }
                    }
                    return@post
                }
                // 焦点真的落到那一行了，待办才作废
                if (holder.itemView.requestFocus()) pending = RecyclerView.NO_POSITION
            }
        }
        return true
    }

    /** 这一行在列表可见区里是不是整行都露着 */
    private fun fullyVisible(list: RecyclerView, view: View): Boolean {
        if (view.height <= 0) return false
        return view.top >= list.paddingTop && view.bottom <= list.height - list.paddingBottom
    }

    /**
     * 焦点搜索失败时的兜底。
     *
     * 系统的机制在这儿：`super` 会补出那一行并 requestFocus。补出来的行在屏幕外，
     * 光标看不见——所以这里再把它滚进可见范围。
     *
     * **滚动必须推迟到 post 里，绝对不能同步做。**
     * 同步滚会立刻让 RecyclerView 回收、搬移子 view，而此刻 FocusFinder 的调用栈
     * 还捏着原来那个 view，回头调 `offsetDescendantRectToMyCoords` 就越界崩掉：
     *
     *     IllegalArgumentException: parameter must be a descendant of this view
     *     at ViewGroup.offsetRectBetweenParentAndChild
     *     at FocusFinder.findNextFocus
     *
     * 作者主页的投稿列表按上键必崩，就是这么来的（真机实测）。
     *
     * 滚法用 `scrollToPositionWithOffset` 而不是 `scrollBy`：后者在这个上下文里
     * 是空操作（首页列表实测连滚六次可见范围一动不动）。
     */
    override fun onFocusSearchFailed(
        focused: View,
        focusDirection: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State,
    ): View? {
        val found = super.onFocusSearchFailed(focused, focusDirection, recycler, state) ?: return null
        val list = found.parent as? RecyclerView ?: return found
        // 只记位置，不碰 view：post 跑起来时这个 view 可能已经被回收复用了
        val position = list.findContainingViewHolder(found)?.bindingAdapterPosition
            ?: return found
        if (position == RecyclerView.NO_POSITION) return found

        list.post {
            val holder = list.findViewHolderForAdapterPosition(position) ?: return@post
            val v = holder.itemView
            val maxTop = (list.height - list.paddingTop - list.paddingBottom - v.height).coerceAtLeast(0)
            when {
                // 上面被裁：顶到列表上沿
                v.top < list.paddingTop -> scrollToPositionWithOffset(position, 0)
                // 下面被裁：底到列表下沿
                v.bottom > list.height - list.paddingBottom -> scrollToPositionWithOffset(position, maxTop)
            }
        }
        return found
    }

    /**
     * 一行的高度。
     *
     * 不能用第一行的高度：第一行可能被裁掉一半（列表边缘那行只露一半），
     * 拿它当行高，滚的距离就不对。
     * 所以取所有可见行里最高的那一个。
     */
    private fun rowHeight(): Int {
        var max = 0
        for (i in 0 until childCount) {
            val h = getChildAt(i)?.height ?: 0
            if (h > max) max = h
        }
        if (max > 0) return max
        val first = findViewByPosition(0)
        val density = first?.resources?.displayMetrics?.density ?: 1f
        return (48 * density).toInt()
    }
}
