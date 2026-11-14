package com.kunplayer.tv.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs

/**
 * 一行放不下就换行的标签容器。
 *
 * 为什么不用现成的两行 LinearLayout：词的字数是用户改的（设置页可以改成任何词），
 * 「第几个开始换行」在布局期才知道。写死 10 + 5 两行，用户把前十个词改长一点，
 * 第一行就又溢出了；而 FlowLayout 不必预知数量。
 *
 * 方向键不交给 FocusFinder：它按几何位置挑，两行之间的斜向移动会跳到不相干的地方，
 * 也会把「行尾 → 下一行行首」判成「右边没人」。这里自己按行列算，
 * 规则和人读表的习惯一致：[move] 只在流内部移动，出了流的边界返回 null，
 * 由调用方决定去哪（上面是侧栏、下面是「播放全部」）。
 */
class ChipFlow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    private val gapX = dp(6)
    private val gapY = dp(8)

    /** 上一次布局算出来的行。按键时按它算邻居，不在按键时重新量 */
    private var rows: List<List<View>> = emptyList()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val avail = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            measureChild(c, widthMeasureSpec, heightMeasureSpec)
        }
        val lines = pack(avail)
        var height = paddingTop + paddingBottom
        lines.forEachIndexed { i, ln ->
            height += ln.maxOf { it.measuredHeight }
            if (i > 0) height += gapY
        }
        setMeasuredDimension(
            resolveSize(avail + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(height, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val lines = pack(width - paddingLeft - paddingRight)
        // 先分行，再按每一行的最高者定位：边排边算行高的话，
        // 第一个视图量到的是「行高 0」，竖着居中会把它顶到行外
        var y = paddingTop
        for (ln in lines) {
            val lineH = ln.maxOf { it.measuredHeight }
            var cx = paddingLeft
            for (c in ln) {
                val h = c.measuredHeight
                val top = y + (lineH - h) / 2
                c.layout(cx, top, cx + c.measuredWidth, top + h)
                cx += c.measuredWidth + gapX
            }
            y += lineH + gapY
        }
        rows = lines
    }

    /**
     * 按可用宽度分行。
     *
     * 第一遍按宽度自然地装，装出几行之后**再按行数均分一次**：
     * 十五个词的按宽度装结果是 13 + 2，第二行戳着两个词很难看；
     * 均分成 8 + 7 才是「两行」该有的样子。
     *
     * 均分的上限用 ceil(总数 / 行数)，一定装得下——自然装出来的第一行
     * 就是这个宽度里能容下的最多数量，比它少的行不可能溢出。
     */
    private fun pack(avail: Int): List<MutableList<View>> {
        val natural = pack(avail, cap = 0)
        if (natural.size < 2) return natural
        val total = natural.sumOf { it.size }
        val cap = (total + natural.size - 1) / natural.size
        return pack(avail, cap)
    }

    private fun pack(avail: Int, cap: Int): List<MutableList<View>> {
        val lines = ArrayList<MutableList<View>>()
        var line = ArrayList<View>()
        var x = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            val w = c.measuredWidth
            val wrap = x > 0 && (x + gapX + w > avail || (cap > 0 && line.size >= cap))
            if (wrap) {
                lines += line
                line = ArrayList()
                x = 0
            }
            if (x > 0) x += gapX
            x += w
            line += c
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    /**
     * 在流内部移动。[direction] 取 [View.FOCUS_LEFT] 等四个。
     *
     * @return 目标视图；出了流（第一行再往上、最后一行再往下、行首往左且没有上一行）
     *         返回 null，移动的交接留给调用方
     */
    fun move(from: View, direction: Int): View? {
        val r = rows.indexOfFirst { row -> row.any { it === from } }
        if (r < 0) return null
        val row = rows[r]
        val i = row.indexOfFirst { it === from }
        if (i < 0) return null
        return when (direction) {
            View.FOCUS_LEFT ->
                if (i > 0) row[i - 1] else rows.getOrNull(r - 1)?.lastOrNull()
            View.FOCUS_RIGHT ->
                if (i < row.size - 1) row[i + 1] else rows.getOrNull(r + 1)?.firstOrNull()
            View.FOCUS_UP -> rows.getOrNull(r - 1)?.let { nearest(it, from) }
            View.FOCUS_DOWN -> rows.getOrNull(r + 1)?.let { nearest(it, from) }
            else -> null
        }
    }

    /** 上一行/下一行里横向离当前这个最近的那个 */
    private fun nearest(row: List<View>, from: View): View? {
        val center = (from.left + from.right) / 2
        return row.minByOrNull { abs((it.left + it.right) / 2 - center) }
    }
}
