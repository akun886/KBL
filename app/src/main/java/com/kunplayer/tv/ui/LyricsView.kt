package com.kunplayer.tv.ui

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kunplayer.tv.R
import com.kunplayer.tv.cache.Settings
import com.kunplayer.tv.net.LyricsApi
import kotlin.math.abs

/**
 * 歌词。只负责画，不接管任何按键——播放页的方向键在派发层就映射成切歌了，
 * 这一层要是可聚焦，就会变成「按右键先跳到歌词再按一次才切歌」。
 *
 * 三件事决定了它的形状：
 *
 * 1. **上下留白到半屏。** 首尾两行也要能滚到正中，否则第一句永远贴在顶上，
 *    「已经唱到哪儿了」就少了一半信息。留白在 [onSizeChanged] 里按实际高度算。
 * 2. **当前行只换颜色，不换字号。** 改字号就改行高，居中位置跟着抖一下；
 *    歌词是整屏唯一在动的东西，抖一下全场都看得见。
 * 3. **跳得远就直落，跳得近才滑。** 一句一行地推进时平滑滚过去才有「唱过去」的感觉；
 *    用户手动切歌跳了几十行，再慢慢滚就是浪费他的三秒。
 */
class LyricsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : RecyclerView(context, attrs) {

    private companion object {
        /** 单行高度估个大概，只用于算留白；真实高度由 RecyclerView 量 */
        const val ROW_H_DP = 34

        /** 跨这么多行以内用平滑滚动，超过就直接定位 */
        const val SMOOTH_SPAN = 6

        /** 还没开始播（没有当前行）时整块字幕的亮度 */
        const val FALLBACK_ALPHA = 0.55f
    }

    private val inner = Adapter()
    private var lines: List<LyricsApi.Line> = emptyList()
    private var index = -1

    /** 基准字号。设置里的「歌词大小」是在这个数上乘倍数，不是换一个新的绝对值 */
    private var baseSp = 22f

    /** 当前字号倍数、颜色、字体。每次 applySettings 从偏好里重读 */
    private var sizeScale = 1f
    private var textColor = 0xFFFFFFFF.toInt()
    private var fontMode = Settings.LYRIC_FONT_DEFAULT

    val isEmpty: Boolean get() = lines.isEmpty()

    init {
        layoutManager = LinearLayoutManager(context)
        adapter = inner
        itemAnimator = null
        isFocusable = false
        clipToPadding = false
        overScrollMode = OVER_SCROLL_NEVER
        applySettings()
    }

    /**
     * 设置里改过歌词的颜色 / 字体 / 大小：重取一遍，并把行的上下留白重新算一次。
     *
     * 留白必须跟着字号走：22sp 的行高和 32sp 的行高差一半，留白仍按旧值算的话，
     * 首句就落不到屏幕中线，「唱到哪儿」靠眼睛判断的那点信息会歪一截。
     */
    fun applySettings() {
        sizeScale = Settings.lyricsScale(context)
        textColor = Settings.lyricsColor(context)
        fontMode = Settings.lyricsFont(context)
        inner.notifyDataSetChanged()
        applyPadding(height)
    }

    private fun face(active: Boolean): Typeface? = when (fontMode) {
        Settings.LYRIC_FONT_HEI ->
            Typeface.create(if (active) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        Settings.LYRIC_FONT_SONG -> Typeface.create("serif", Typeface.NORMAL)
        else -> if (active) Typeface.create("sans-serif-medium", Typeface.NORMAL) else null
    }

    fun submit(next: List<LyricsApi.Line>) {
        lines = next
        index = -1
        inner.notifyDataSetChanged()
        scrollToPosition(0)
    }

    fun clear() = submit(emptyList())

    /**
     * 把当前行推到正中。跟着播放进度每 500ms 调一次，同一行内不做任何事——
     * 重绑是整屏唯一会打断滚动的东西。
     */
    fun seekTo(positionMs: Long) {
        if (lines.isEmpty()) return
        val target = indexAt(positionMs)
        if (target == index) return
        val from = index
        index = target
        // 透明度是按「离当前行几行」算的，所以换行时不能只刷新旧两行：
        // 原来在下一行的那句会从 0.16 变成 0.62，不重绑就还停在旧色上。
        // 全量通知听起来重，其实只重绑当前可见的十来行，一次换行一次
        inner.notifyItemRangeChanged(0, lines.size)

        val lm = layoutManager as? LinearLayoutManager ?: return
        val anchor = paddingTop
        // 目标行已经在屏幕里就滚过去，滚动的过程本身就是「唱到下一句」的提示；
        // 不在屏幕里（切歌跳了几十行）就直接落位——让用户盯着一串飞过的字没有意义
        val view = findViewHolderForAdapterPosition(target)?.itemView
        if (from >= 0 && view != null && abs(target - from) <= SMOOTH_SPAN) {
            smoothScrollBy(0, view.top - anchor)
        } else {
            // offset 是相对**内容区**（padding 之后）的，不是相对控件顶部。
            // 传 anchor 会再叠一次 padding，当前行就落到下半屏去了
            lm.scrollToPositionWithOffset(target, 0)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 半屏留白：首行和末行才能落在中线
        applyPadding(h)
        if (index >= 0) {
            (layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(index, 0)
        }
    }

    private fun applyPadding(h: Int) {
        if (h <= 0) return
        val row = (ROW_H_DP * sizeScale * resources.displayMetrics.density).toInt()
        val pad = ((h - row) / 2).coerceAtLeast(0)
        setPadding(paddingLeft, pad, paddingRight, pad)
    }

    /** 从后往前找最后一个不晚于当前播放位置的行；都比它晚就停在第一行 */
    private fun indexAt(ms: Long): Int {
        for (i in lines.indices.reversed()) {
            if (lines[i].startMs <= ms) return i
        }
        return 0
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {

        inner class Holder(view: View) : ViewHolder(view) {
            val text: TextView = view as TextView

            fun bind(line: LyricsApi.Line, active: Boolean, distance: Int) {
                text.text = line.text
                // 字号由设置决定：基准 22sp × 倍数。用 setTextSize 而不是 scale，
                // 这样「把字调大」是真的把字调大，行高和居中都跟着走
                text.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSp * sizeScale)
                // 颜色只有一处真源（设置里的歌词颜色）。当前行与其它行的差别交给
                // 透明度和字重——两套颜色会在换色时把「当前行」也一起换掉，反而看不出是哪句
                text.setTextColor(textColor)
                // 越远越淡。原来一律压到 0.55：隔一行和隔八行一样亮，整屏字幕没有落点。
                // 分级之后屏幕边缘那几行几乎看不见，也就不必再叠一层渐隐——
                // 遮罩压在高斯模糊的背景上会露出一条直边，反而更像渲染错误
                text.alpha = if (index < 0) FALLBACK_ALPHA else alphaAt(distance)
                text.setTypeface(face(active))
                // 当前行放大一号。用 scale 而不是改 textSize：textSize 会改测量高度，
                // 居中的那一行自己把行高撑大，滚动位置就跟着抖；
                // scale 只在绘制层放大，布局一个像素都不动
                val scale = if (active) 1.14f else 1f
                text.scaleX = scale
                text.scaleY = scale
            }
        }

        override fun getItemCount(): Int = lines.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_lyric, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(lines[position], position == index, abs(position - index))
        }
    }

    private fun alphaAt(distance: Int): Float = when {
        distance <= 0 -> 1f
        distance == 1 -> 0.62f
        distance == 2 -> 0.42f
        distance == 3 -> 0.28f
        else -> 0.16f
    }
}
